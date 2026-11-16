# TLC-1 Final Integration Gate (2026-11-10)

## Scope and protected baseline
- Android source: pavanpatil93-dot/Tlc-1, branch integration/tlc1-final-candidate-20261110, based on 4073cecc231571790cddbef12afdafc7e43b7263.
- Existing PR #38 remains open. Do not merge it or deploy to production without explicit approval.
- Gemini AI Studio target URL: https://aistudio.google.com/apps/aee7d7fd-92c0-40eb-8783-a63ac42b2a7a
- Gemini source not yet available for inspection. A preview URL does not provide source files or edit permissions.

## Non-destructive integration rules
1. Obtain exported Gemini source ZIP or accessible GitHub repository before attempting cross-project merge.
2. Audit both project frameworks, dependencies, screens, models, API integrations and credentials (do not commit secrets).
3. Preserve all original property records, legacy codes/IDs, contact and provenance fields; treat unknown values as unknown.
4. Back up Google Sheets and local app data before any import; verify backup row counts and hashes.
5. Never call local clearAll(), replace production sheets, or run destructive migration during initial integration.
6. Build an explicit field mapping and conservative deduplication policy; review conflicts instead of silently overwriting.
7. Keep owner/POC details internal; test employee-specific WhatsApp output for contact leakage.
8. Validate CRM, commercial and residential inventory, edit, search, filters, media, sync, and offline persistence.
9. Build and test an integration candidate; require user approval before production merge or deployment.

## Baseline inspection notes
- Native Kotlin/Compose app includes PropertyDetailScreen, ExploreScreen, login, CRM and sync screens.
- SheetsRepository.kt implements sync but DEFAULT_MASTER_SHEET_URL is empty; live connection is not established by code inspection.
- PropertiesLocalDatabase.kt uses JSON file serialization and includes a clearAll operation; do not assume Room is deployed.
- Existing Android CI on baseline commit 4073cecc passed (run 42680736228). This is not a combined Gemini build.

## Blocker
Export Gemini AI Studio app as ZIP and attach it, or provide its accessible source repository. Once source is obtained, compare and implement on this integration branch, not on main.
