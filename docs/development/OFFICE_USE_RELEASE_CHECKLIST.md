# Office-use release checklist — 12 November 2026

This urgent scope replaces the earlier order until office-use essentials pass. Defer Auto-fetch, project enrichment/media, social analytics, PDF proposals and advanced features. Preserve all inventory/CRM data. All existing authorizations for routine code changes and builds remain; no repeated next-action questions are needed.

## Team and requested access (not provisioned authentication)

| Person | Business role | Reports to | Intended account access |
|---|---|---|---|
| Pawan Patil | Owner / Administrator | — | Admin: add/edit/manage; confidential data and request approval |
| Sagar Chaudhari | Sales Manager / Administrator | Pawan | Admin: add/edit/manage; confidential data and request approval |
| Himanshu | Sales Executive | Management | General inventory; assigned leads; request confidential details |
| Ayush | Sales Executive | Sagar | General inventory; assigned leads; request confidential details |
| Parth | Marketing and back-office support | Management | Approved marketing/inventory support; request confidential details |

Interpret user's “adding access” for Pawan/Sagar as administrator access. Do not authorize by typed name, locally selectable role, or an unverified mobile/email. Verified account IDs/provisioning are missing; do not silently assign Sagar Admin by a known business contact alone. Existing employee-name/mobile login is not verified authentication.

## Inventory and search

- [x] Earlier Samsung tests passed: listing, search locality/code, filters/clear, details, add/edit/status, auto codes and required-field errors, offline/restart, share privacy/POC and multi-share, dialer/maps.
- [ ] Verify current combined build and APK; no fresh regression pass is implied by old phone tests.
- [ ] One readable full-width card, large photo or labelled unavailable placeholder, current amount and correctly labelled carpet/built-up rates.
- [ ] Confirm favourites, photo orientation, essential controls and no zero-as-unknown prices.
- [ ] Preserve private fields when editing public listing fields; no destructive exports/imports or accidental contact leakage.

## CRM home, leads and flow

- [ ] All assigned/open leads visible; separate Today/Overdue/Upcoming; no priority filter silently hiding the full pipeline.
- [ ] Approved compact cards: client/phone, type/transaction, budget, area and location if recorded, stage, assigned employee, latest note, date/time follow-up.
- [ ] Working Call, WhatsApp, Log, Follow-up, Find Properties, Edit and assignment controls.
- [ ] Lead stages: New → Contacted → Follow-up → Details Shared → Exploring Market → Site Visit Pending → Site Visit Done → Negotiation → Closed Won / Closed Lost.
- [ ] Separate Hot/Warm/Cold from pipeline stage during schema consolidation; do not invent temperature.
- [ ] Save/reopen/restart, duplicate normalized mobile handling, stage changes, activity history, ownership and team reporting verified.
- [ ] Sales users see permitted assigned clients; Sagar sees managed team/assigned leads; user visibility must be enforced by shared backend before production.

## Date and clock follow-ups

- [ ] Native date picker and clock/time picker in lead form, card follow-up dialog and call review; no repeated manual typing.
- [ ] Save date and HH:mm separately, preserve old date-only leads and time on edits, reject invalid time/date.
- [ ] Show selected time; classify same-day passed times as overdue.
- [ ] Calendar/clock picker does not imply notification scheduling. Reminder notification scheduling, timezone/restart recovery and permission-denied fallback still require integration and device tests.

## Confidential information and approval requests — RELEASE GATE

Default confidential scope: owner/broker names and direct contacts, source details, internal remarks, internal financial/commission notes and private change history. Client contact visibility follows assigned-lead permissions; public price/area/property details remain usable.

Required flow:
1. Staff sees “Restricted • Request access” rather than private values.
2. Request includes employee identity, exact record, requested fields and reason; prevent duplicate pending requests.
3. Both admins receive a real cross-device notification and see a shared approval queue.
4. An authenticated admin approves/declines with audit identity/time/reason.
5. Approved employee can read only granted scope from then on, including after restart; admins can revoke.
6. Server checks apply to reads, exports, search, sharing, project/source JSON, attachments and background sync; private data must not be bundled into staff APKs or unrestricted Sheet downloads.
7. Network failure never silently approves access; stale approvals cannot broaden scope; requests and grants persist across devices.

Current verified blockers: no configured Firebase/authenticated backend or server-held team registry was found; app has local employee-name/mobile login and bundled private inventory. Existing Settings can self-select role and new registrations inherited Admin; remove those shortcuts, but do not call UI hiding production security. No cross-phone approval notification can be claimed until a real shared service, identity and delivery are configured. Do not distribute an unrestricted actual-data APK to staff as a confidential-data-protected release.

## Current implementation batch

Native date/clock controls, persisted lead follow-up time, Follow-up pipeline stage, least-privilege fresh-account defaults and disabled self-role promotion. Move identical compressed project seed into Android raw resources so APK and Robolectric use the same packaged dataset; keep 928-data assertions. Add focused regressions. CI and Samsung verification pending.

## Immediate execution

1. Fix exact build failure and verify test/compile on new SHA.
2. Verify inventory/search and CRM essentials; finalize date/time picker and safe persistence.
3. Implement authenticated team registry and real request/approval/notification/grant path. Collect verified login identifiers for Pawan/Sagar/Himanshu/Ayush/Parth; never guess identities or store passwords in source.
4. Resolve artifact quota and stable APK signing/update backups.
5. Deliver an office candidate only with explicitly tested scope. Non-confidential functional testing may precede production access approval.
