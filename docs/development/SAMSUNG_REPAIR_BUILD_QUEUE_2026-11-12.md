# TLC-1 Samsung repair and development build queue

Updated: 12 November 2026, India. Authoritative working queue for the hourly TLC-1 automation.

## Authorization and execution

The user requests consecutive builds without repeated permission prompts: finish the MVP repairs, then implement the advanced queue below. Start from integration/google-ui-tlc1-20261112 (PR #61), resolving live HEAD each run. Preserve existing inventory, CRM, codes, contacts, owner/POC, provenance and unknown fields. Never clear data to make a test pass. Use separate integration branches and exact-SHA CI. Safe verified merges are authorized by the existing development-watch rule; do not merge unverified or known-broken work. No landscape work: explicitly deferred by user.

Check hourly. Fix verified failures first. A queued/running build is neither passed nor failed. Do not trigger duplicate workflows or pile dependent changes onto unverified builds. Prepare independent work while waiting. Each batch needs code review, targeted automated checks, APK compilation and relevant acceptance results. Build success does not prove Samsung usability. Mark device-only checks pending until reported or actually executed. Maintain this document with commit/run/artifact/status evidence. Pause automation after the finite queue is complete; do not invent endless features.

## MVP queue

| Batch | Work | Acceptance | Status |
|---|---|---|---|
| B01 | Samsung stabilization: restore visible CRM leads; information-rich cards and call/WhatsApp/edit/match actions; visible manual call entry; Samsung-compatible call-log query and errors; explicit matching submit and conservative filtering; labelled carpet/built-up rates; working persistent favourites; EXIF orientation and manual photo rotation; reachable Sheet URL/test/sync controls | Saved leads appear without urgent follow-ups; cards accessible on portrait; saved calls never lost; import twice without duplicates; matching excludes wrong location/type/transaction/budget/area/unavailable records; missing info labelled unknown; saved favourite survives restart; new and older photos can display upright; rates agree with amounts/areas; Sheet controls visible | Implemented in source; CI and device verification pending |
| B02 | Project listing foundation: Commercial Projects and Residential Projects screens; project cards/detail/search; select existing project or explicitly create before unit entry; link each unit to stable project ID; view all statuses within project; support unverified projects with review label | Select/create project first; no false verified/developer labels; aliases and duplicate project names reviewed; sold/leased/unavailable units accessible within project; existing units preserved with migration/rollback checks | Next; new Sheet tabs created, headers only |
| B03 | Five-tab Sheet integration: preserve CRM/Commercial Inventory/Residential Inventory and add project-directory mapping; fix real connection diagnosis; refresh exact tab mapping; restricted authenticated read access; conservative dedup and no-overwrite local edits | Exact URL and tabs visible; distinguish configured/verified/error/local bundled records; verify live returned rows; repeat pull creates no duplicates; errors actionable; project tabs never parsed as unit inventory | Pending; do not expose private Sheet or claim live sync passed |
| B04 | Portrait usability and theme: persisted Light/Dark/System switch, coordinated contrast across cards/forms/dialogs, concise lead cards with area/furnishing/timeline when recorded, all critical actions reachable; remove Portfolio UI wording | System and manual modes readable; no invisible text, overflow or clipped buttons; no invented missing lead fields | Pending; no landscape changes |
| B05 | MVP release audit and remaining checks: ROI, completion-score rules, area field behaviour (Test 47 unanswered), CRM add/edit/retrieve, call notes/follow-up/conversion, project-first flow, photo orientation, saved favourites, live read sync; regression on all passed workflows | Exact-SHA tests/build/artifact/hash; data survives update/restart/offline; no destructive migration; device tests explicitly distinguished from CI; publish candidate APK and migration-safe install notes | Pending |

## Advanced queue (only after MVP acceptance; independent preparation allowed)

| Batch | Work | Acceptance |
|---|---|---|
| A01 | Follow-up reminders, due/overdue agenda and Samsung permission/battery handling | Reminder scheduling survives restart/time-zone changes; duplicates avoided; denial leaves manual workflow working |
| A02 | Actual-record deal pipeline and site visits | Replace samples; link actual leads/properties/team; visit scheduling/outcomes persist; no invented revenue or commission |
| A03 | Authenticated two-way sync with audit, version/conflict detection and safe retries | Proven authentication and acknowledgements; idempotency; conflicts reviewed; local edits never silently overwritten; rollback tested before enabling writes |
| A04 | Project intelligence and matching refinement | Verified source links/RERA and freshness labels; possession, parking, workstations and ROI checks from structured recorded values; alternatives explicitly explain relaxed constraints; no false exact claims |
| A05 | Map exploration, employee work queues and operational reporting | Real coordinates; offline fallback; recorded metrics only; owner/employee boundaries respected; no personal-data leakage |

No paid services, exposed credentials, public sharing, automatic client/team messaging or unsupported authentication bypass. Prepare blocked integration code and report exact access blocker; continue other independent tasks.

## Project Sheet contract

Spreadsheet ID: 1VJruPx1GTOMDziafPYK6ihW1v1DGrqTWO2vHk_oNReQ.
The user explicitly expanded the prior three-tab restriction to five tabs on 12 November 2026:

- CRM (existing; preserve)
- Commercial Inventory (existing; preserve)
- Residential Inventory (existing; preserve)
- Commercial Projects (sheetId 2010; 34 headers; created with no project rows)
- Residential Projects (sheetId 2011; 33 headers; created with no project rows)

New directory tabs include project code/name, developer, locality/micro-market/zone, category, configurations/types, carpet range, price/rent fields, RERA/source URLs, approvals, status/possession, land/tower/floor/amenities, map/coordinates, verification/freshness, assigned employee/contact and timestamps. Unverified / Needs Review / Verified / Conflicting are distinct. Directory row grain is one identifiable project/phase; use linked configuration records rather than duplicate units. Preserve existing project codes from source; auto-generate only new IDs. Never infer approvals, possession, developer or verification from a project name. Import actual project records from available source-backed directories, retain provenance and unknown fields, audit ambiguous project aliases instead of inventing data.

## Samsung evidence supplied by user

Passed: install, launch, inventory visibility, Kharadi and code search, property details, single/multiple share previews, share contact and privacy, offline/restart, property add/edit/status, dialer, filters/clear, collapsing header/search, returning list position, login persistence, numeric carpet validation, auto codes, edit retention, required-field errors, no-results, unsaved-change confirmation. Completion indicator visible; scoring unverified.

Failures/requested changes: CRM opens but leads missing, lead-card redesign; device call-log sync absent; no visible manual Add Call/Note; matches lacks submit and dependable rules; Sheet not connected and URL field not found; favourites does nothing; photos rotated 90 degrees; dark-mode switch missing; inconsistent rate basis; project-first flow absent and misleading verification label. ROI not tested. Area field interaction (Test 47) unanswered. Landscape deliberately deferred. Do not ask more CRM tests until repaired.

## Run ledger

- Prior tested candidate: branch integration/google-ui-tlc1-20261112 at 346a956981ace9188ecaee455f9d29abb1df6480, PR #61.
- Current B01 source changes prepared against that SHA. CI and physical acceptance must be recorded after verification; never label this build released from source edits alone.
- Hourly TLC-1 Build Queue automation: Automation_fc773370401c81919feeea92a658566a, updated rather than duplicated.


## Screenshot evidence supplement (12 November 2026)

All ten Samsung screenshots reviewed. These are evidence from the previously installed candidate, not proof B01 has been installed or tested.

| Image | Evidence and required correction |
|---|---|
| 1000814566.jpg | Matches show 508 candidates and 98% scores for a 500 sq.ft unit and a ₹1.78 lakh rent against 1,000–1,500 carpet and under ₹1 lakh. Enforce hard constraints; no exact-score inflation; submit button. |
| 1000814565.jpg | Calls shows zero; Log Call and Sync Device exist. Correct earlier report: manual button is present but unclear. Rename Add Call / Note and Import call log; keep demo tools secondary. |
| 1000814564.jpg | All clients (5) proves saved leads exist. Priorities view and fixed content hide them; Snippets and Upcoming wrap vertically. Default to all leads, scroll content, prevent action/filter text squeezing. |
| 1000814562.jpg | Two-column grid has vertical codes/furnishing and huge empty cards. Use minimum 340dp card width so narrow portrait gets one column. |
| 1000814561.jpg | Same grid defect plus generic imagery repeated; investigate media provenance, never imply stock photo depicts the property. |
| 1000814563.jpg | Oxy Bastion detail shows ₹0 and partially clipped bottom actions. Display Price on request for unknown amount; audit action widths/insets. |
| 1000814559.jpg | Main search still exposes Active/Verified/Pending/Archive and Portfolio. Move verification/archive to advanced filters; rename division selector. |
| 1000814560.jpg | Codes and Unfurnished wrap one letter at a time. Fix adaptive width and protect code single-line. |
| 1000814558.jpg | Gagan Avencia cards show 2,482/1,243 carpet and ₹4.7/2.35 Cr. Audit original source-to-project association; screenshot cannot prove correct project name. Do not overwrite source data from memory. |
| 1000814557.jpg | Anandtara Prime ₹19.7 Lac for 1,149 carpet and ₹1,714/sq.ft requires source/unit audit. Do not silently multiply price by ten without provenance. |

B01 at commit 0f94d2c84da540153302127c761daa1217c525a4 started CI run 43113206914 (#461). Screenshot supplement adds independent portrait fixes. Preserve live records while auditing suspicious associations/prices. B04 must remove primary verification/archive chips, label stock media, and check detail bottom action insets and touch targets. B03/B05 must audit residential source mapping and price units before recommending those records to clients.


## Approved card preference

User selected Large photo on every card. Use a 16:9 property-photo header on every card and one readable card per row on narrow phones. Missing media gets a labelled neutral placeholder, never an invented building photo. Preserve existing images; source/provenance audit remains pending. CI #462 failed the semi-furnished parsing regression; fix the requirement parser ordering, retain the test and verify the next build before release.


## Approved CRM lead cards — implementation

User approved the compact, video-inspired CRM design on 12 November 2026. Implement compact identity/stage/assigned employee/phone, saved requirement/budget/locations, explicit follow-up status, latest recorded call and one-line latest note. Call/WhatsApp/Log/Follow-up are primary actions; Find Properties remains directly accessible; More opens client activity, edit/assignment, site visits. Do not invent temperature, structured area or furnishing when absent. Legacy Hot/Warm/Cold is displayed only when recorded. Log and follow-up dialogs save one atomic lead/activity preference update, preserve unknown raw fields/other leads/previous notes, reject stale snapshots and damaged JSON. New card activities are visible in the client profile. Five Robolectric regressions cover preservation, corruption, stale edits, date validation and non-call follow-up actions. Follow-ups in this batch are date-only, with Today/Tomorrow/In 3 days/No follow-up; exact time shortcuts, dedicated reassignment audit and unified reminders remain pending (A01/A02). Do not claim notification scheduling from saving the date. Prior repair/photo build #463 (43114175547) at 0f8dcf55f0349c8d9e3f7adff430bc4172d0c650 passed CI; current card implementation needs exact-SHA CI and Samsung verification.


## Social media business workflow (user requested 12 November 2026)

MVP foundation: configurable Instagram/Facebook/LinkedIn page shortcuts separately for Ascend Commercial Solutions and Aurea Living in Settings. Each property detail has per-property published video/post URL fields for the same three platforms, open links and user-controlled share chooser. Accounts/URLs are not guessed: exact six company page URLs and a sample property code/reel link must be supplied by user. No passwords or tokens are requested. URLs restricted to proper platform HTTPS domains. Mapping uses stable property ID; linking the same project-level video across units requires explicit selection, never inference. Links live in local social_links_v1 preferences; no property fields or live Sheet rows are overwritten. Backup/restore and shared five-tab Sheet mapping for these new fields remain pending B03/B05 before claiming team-wide sync. Existing generic source images are not verified by adding a social URL. Source changes and three new regressions need CI and device acceptance.

A06 — Social marketing workflow (after MVP): Add share-to-TLC intake of copied post/reel links for manual property selection; project-level versus unit-specific videos; campaign/source fields on CRM leads with manual attribution first; tracked share events labelled accurately (a share-chooser open is not delivery, view, or enquiry); approved caption and campaign draft generation from verified availability/prices; media status/library; authenticated professional account post ingestion and proposed property matches with review; insights and campaign-to-enquiry-to-visit-to-deal reporting only with granted access and actual source data. OAuth, server-held credentials, platform permissions/access review and clear user-controlled publishing are required for real feed/inbox/publishing/analytics integration. Do not claim a URL shortcut connects accounts, imports DMs, identifies viewers, reads all comments, or auto-publishes. Confirm current official APIs before implementation. No publishing, sending client messages, paid ad spend or scraping is authorized by this feature request. Drafts and prepared account-connection flows are in scope.


## B02 required addition — Auto-fetch Project Details

User explicitly requested auto-fetch of commercial and residential project details, floor plans, cover photos and other available online information on 12 November 2026. This is a required project-listing feature, not a claim that fetching is already operational. Implement source enrichment after stable project identities and a staged review path; keep it within the existing automated build queue.

### User flow

Enter project name + locality + developer, or paste official project URL / brochure / RERA reference. Tap Fetch online details. Show candidate projects and confirm exact project/phase/tower/configuration identity before applying anything. Discover official sources through a supported search provider/backend; do not scrape Google result pages or ship API secrets in the APK. Always support direct official URL/brochure input as a fallback. Fetch drafts of identity, developer, location, map reference, RERA registration, category/configurations, carpet range, amenities, construction status, stated possession, pricing and brochure/floor-plan/media references when present. Missing fields remain unknown. Every field/media item retains source URL, publisher, retrieval time, relevant phase/configuration and review status. Search position is not verification.

### Source selection and record rules

Prefer the official developer/project page and published brochures for marketing details/media; official MahaRERA records for registration/phase evidence where accessible. Use Google Places only for supported location/photo lookups through a configured integration, with required attribution and storage restrictions; Google search discovery is not an image download license. Portals and public social posts are supplementary evidence, clearly labelled. Do not infer approvals, legal title, bank loans, price inclusions, actual availability or possession commitments. Advertised starting price remains distinct from an owner's unit asking price; carpet remains distinct from saleable/built-up. Source-stated possession, RERA completion and employee-confirmed possession are separate concepts. No silent price-unit multipliers or project remapping.

### Cover images and floor plans

Present candidate covers as actual site photo / architectural rendering / stock illustration / unknown; let employee select the correct cover. Only download and persist assets when access and usage terms allow; otherwise retain a source/reference link and attribution. Do not crop away required attribution/watermarks. Keep floor plans separate from gallery photos, tagged with project, phase, tower, configuration, area basis, source and revision when supplied. Preserve brochure PDFs and source page references; extracting a diagram from a brochure must retain page evidence and usage context. Do not assign a generic 3 BHK plan to all 3 BHK units. Do not calculate or claim exact carpet from an unscaled/uncertain plan. No generative substitutes for missing project photos or plans.

### Review / persistence / refresh

Fetched candidates stay in staging with field-by-field before/after, conflicts and sources. Default is fill missing after employee review; never overwrite existing project/inventory/owner/CRM fields in a background fetch. Refresh creates a new proposal/version and keeps original sources/assets. Cover replacement, phase changes, areas, pricing and possession need explicit acceptance. New fetch timestamps mean checked, not verified. Unknown/unverified projects remain selectable. A repeated fetch must not duplicate projects, configurations or identical assets. Respect unavailable/removed sources, login requirements, robots/terms and rate limits; do not bypass CAPTCHA. Surface progress, partial results, no-source-found, network/auth errors, and offline saved records. Shared Sheet mapping uses project IDs and five-tab schema; data/asset references and source evidence sync only after conflict-safe mapping is implemented.

### Build sequence and acceptance

B02.1: project identity + staging schema + official-URL/brochure intake, review controls and provenance tests. B02.2: configured search-provider discovery + source adapters + candidate identity matching; review before source association. B02.3: cover and plan candidate previews with attribution, revision/configuration labels and allowed storage. B02.4: accepted-field persistence, project Sheet references, safe refresh and rollback. Provider access/credentials/cost decisions are blockers only for that adapter; prepare direct-source flow and keep independent work moving. Acceptance fixtures: same-name projects in different Pune localities, multiple phases/RERAs, conflicting possession/price, carpet vs built-up, missing metadata, broken URLs, unsupported PDF/image, duplicate fetches, stale reviews and preservation of employee-entered values. Run automated checks and device tests before marking online enrichment operational.


## B02 data integration checkpoint — 12 November 2026

Hourly automation confirmed enabled and updated with approved designs and automatic next-action selection. Imported 399 commercial and 529 residential project candidates from TLC1_Master_Inventory_With_Projects_2026-11-11.xlsx into existing project tabs (2010/2011). Every cell in all 928 rows was read back and compared; later source-document-status updates also read back. Original source fields/verification claims retained in Source Record JSON and separate original-claim columns. Current verification is Needs Review or Unverified, never promoted from inherited labels. Potential locality-only names and aliases remain unresolved with review notes; do not merge by name alone or call these 928 distinct verified buildings. Existing CRM and unit-inventory tabs untouched.

App source: compressed read-only seed of the same 928 codes + source evidence, new searchable commercial/residential Project Directory route in drawer, project details/source/document-candidate links, employee-created local projects, and explicitly confirmed unit links stored separately. No destructive Room/schema migration and no writes to existing unit records. Online search opens a browser; automatic source extraction/review from approved mockup is still pending. Cover photos and floor plans are not invented. New local projects and unit links are not yet synced to the Sheet; all five-tab mapping/backup coverage remains B03/B05 work. Add Property from project and comprehensive project-first enforcement still pending. Three Robolectric tests cover code/data preservation, local create/duplicate and conservative link behaviour. Exact-SHA CI and Samsung acceptance pending.

Source retrieval: 97 unique original source URL references; 36 source pages retrieved from public official/developer domains and 19 unique PDFs downloaded. PDFs include a brochure and multiple certificates/environment/compliance documents; these are not 19 validated floor plans. Several developer landing-page documents cover other projects/cities; association must be reviewed before attaching to an exact project/phase. Portal and supplementary sources retained as references, not bulk copied. Access failures and unsupported references recorded in manifest. Source package saved as TLC1_Project_Source_Package_2026-11-12.zip, Library ID libfile_a45ac72e68508191948a9a08efdc306b. Never state all sources/documents fetched. Use its 928-row source audit to prioritize missing official project pages and plan/brochure retrieval next.

CI #465 at c645f4b1393f6f6b350e70bce1765975c2b289dd passed unit tests and assembleDebug but failed artifact upload due to GitHub artifact storage quota. Do not call the overall run passed or invent an APK download link. Reduce future artifact retention to 3 days; existing artifacts not deleted. Quota recalculation/delivery remains blocked until capacity is available. Avoid retry storms. Current CI signing creates a fresh debug keystore per build: stable upgrade-safe signing and local-data backup must be resolved before asking employees to replace an installed APK; do not advise uninstall/clear-data as a routine fix.


## URGENT PRIORITY OVERRIDE — office essentials

The user's latest instruction defers advanced features and project enrichment. Read OFFICE_USE_RELEASE_CHECKLIST.md first and finish inventory/search, approved CRM home/cards, leads/stages, date-and-clock follow-ups, and team confidentiality/access approvals. Pawan and Sagar are intended admins, Himanshu/Ayush sales (Ayush reports Sagar), Parth marketing/back-office. Shared authenticated access approvals/notifications and verified identity remain a production release gate; no superficial local demo claims. Essential source change moves the identical compressed dataset to an Android raw resource, adds native date/time pickers and persisted followUpTime, adds Follow-up stage, removes fresh-account Admin defaults and profile self-promotion. Existing tests retained and focused regressions added. Preserved concurrent automation commit 9b7dd270 adding src/main/assets to test resources; no overwrite. Exact-SHA CI pending. Do not distribute private actual-data APK as confidential-data-protected until backend enforcement is real.

## Verified APK delivery gate (12 November 2026)

- Integration HEAD `90d8b76c84400ae242335b238c5913eab1cc83f5`; PR #61 remains draft.
- Exact-commit CI: https://github.com/pavanpatil93-dot/Tlc-1/actions/runs/43173110666 (run #472). Android unit tests and `:app:assembleDebug` passed. Workflow conclusion is **failure** because `actions/upload-artifact` returned `Failed to CreateArtifact: Artifact storage quota has been hit`; this run has **zero downloadable artifacts**.
- No APK checksum or Samsung installation result can be asserted from this run. The binary existed on the ephemeral runner, not as a retrievable workflow artifact.
- Delivery repair is blocked on GitHub Actions artifact quota recalculation/cleanup or an authorized supported alternative. Do **not** repeat builds against unchanged quota, claim a downloadable APK, or delete unreviewed artifacts. Inspect quota and existing artifact retention before any cleanup. Resume an exact-SHA build/upload only after capacity is verified; record artifact URL/hash and physical acceptance separately.
- Urgent office-use release gates remain: inventory/CRM persistence and date/time follow-ups, authenticated team roles, server-enforced confidential field grants and cross-device approval/audit. Compilation is not proof of these controls. Do not distribute to staff until verified.
