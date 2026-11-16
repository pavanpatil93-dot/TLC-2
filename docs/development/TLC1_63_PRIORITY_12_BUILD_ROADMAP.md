# TLC-1 consolidated 63-priority execution roadmap
Date: 2026-11-12. User explicitly authorized executing all previously discussed priorities, sequentially, with integrated batches. This is the planning backlog; docs/development/SAMSUNG_REPAIR_BUILD_QUEUE_2026-11-12.md remains authoritative for current MVP acceptance and Samsung evidence.

## Rules
- One active integration batch at a time; fix failing CI first, test exact SHA, do not launch duplicate workflows. Keep main untouched until reviewed and approved. Preserve all five Sheet tabs, CRM/inventory, IDs, contacts, provenance, local edits and backups. No invented data, simulated functionality labelled real, public Sheets, automatic external messages, paid services, or insecure authentication shortcuts.
- First deliver office essentials and backend confidentiality. Do not defer a security prerequisite merely because its full feature group appears later. Resolve current GitHub Actions artifact quota and signing/update issues before claiming a downloadable staff-ready APK. Physical Samsung checks remain pending until actually executed.
- Google AI Studio is connected to main and has divergent unsynced files. Do not use its 7-file all-or-nothing Keep controls. Compare the user's fresh AI Studio export with integration/google-ui-tlc1-20261112 on protected integration/ai-studio-reconcile-20261112; merge carefully and test. No direct AI Studio push is available from GitHub connector.
- Every batch: inspect latest HEAD and existing implementation; identify gaps rather than rewriting completed features; write targeted tests; commit safely; verify CI and artifact; log source SHA, tests, blockers and Samsung checks. Tasks below are feature groupings, not claims of implementation.

## 12 integrated builds (63 numbered priorities)
| Build | Name | Original priority IDs | Completion gate |
|---|---|---|---|
| 01 | APK recovery, backups and release foundation | 1,21 | Verified signed/debug update path, accessible artifact and recovery/rollback |
| 02 | Core inventory and lifecycle | 2,25,43,45,47 | Search, validation, statuses, duplicates, availability and vacancies with actual records |
| 03 | CRM, calls and follow-ups | 7,8,26,35,38,44,63 | Lead lifecycle, activity, date/time pickers, visits, reassignment; data persists |
| 04 | Secure employee and admin access | 3,11,24,33,39,57 | Authenticated identity, server-enforced field permissions, audit, cross-device approvals |
| 05 | Five-tab Sheets and project directory | 4,5,23,28 | Safe sync, stable projects, owner/developer links, mandates and conflict resolution |
| 06 | Portrait UI, media and sharing | 6,13,14,58,59 | Accessible one-column cards, correct photo provenance, approved share and media |
| 07 | Matching, shortlisting and clients | 9,20,32,40,62 | Hard-constraint matching, explainable scoring, comparisons and secure client views |
| 08 | Field operations and transactions | 19,31,34,36,42,53,55 | Visits, proposals, due diligence, renewals, history and check-ins |
| 09 | Finance and business intelligence | 12,22,27,30,46,48,51,54,60 | Auditable commission/ROI/forecast/attribution and daily management reports |
| 10 | Source-backed data and market intelligence | 16,17,29,49 | Permitted ingestion, provenance, extraction and human review |
| 11 | Marketing and partner network | 18,37,41,50,56,61 | Controlled publishing, referrals, inquiry inbox and knowledge base |
| 12 | AI, voice and map intelligence | 10,15,52 | Verified geodata, consent-aware voice and source-grounded assistant |

## Dependency-aware order
- Release 1: Builds 01-04, but design authentication and sensitive data access BEFORE releasing Builds 02-03 to staff.
- Release 2: Builds 05-09 after release-1 acceptance.
- Release 3: Builds 10-12 only after trusted data, permissions and operational reporting.
- Maintain separate status per original priority: not started / existing partial / implemented / CI verified / Samsung verified / blocked. A build may be partially complete without being released.
- Finish the finite backlog and stop automation when accepted; never expand to an endless queue without another user request.
