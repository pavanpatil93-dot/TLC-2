# Latest AI Studio ZIP intake audit — 2026-11-11

Source supplied by user: `remix-ascend-&-aurea-os(2).zip`. Inspected locally without executing the archive.

## Confirmed archive contents
- 194 ZIP entries, 63 Kotlin files.
- Native Android/Compose project with Gradle and AndroidManifest.
- Includes `app/src/main/java/com/example/ui/screens/BrokerageDealCalculator.kt`.
- Includes `app/src/main/java/com/example/ui/screens/PuneIntelligenceScreen.kt`.
- Also includes core property, CRM, Sheets repository, detail, search, and sharing code.

## GitHub integration comparison
- Protected integration branch `integration/tlc1-final-candidate-20261110` is the destination.
- Current branch tree contains 60 Kotlin files at inspected head `87e8eed3`.
- The branch does **not** contain the two AI Studio screens above, though it has `BrokerageDealMath.kt` as a partial calculation foundation.
- The branch contains newer work not safe to overwrite wholesale: `MasterProject.kt`, `ProjectInventorySummary.kt`, `BrokerGroupShareFormatter.kt`, and recent PropertyDetail/WhatsApp fixes.

## Merge policy
1. Preserve current GitHub app, data models, inventory identifiers, owner/POC and provenance.
2. Port missing AI Studio screens selectively; adapt imports/navigation and shared calculation services.
3. Avoid copying AI Studio `SheetsRepository.kt`, `PropertiesLocalDatabase.kt`, or `MainActivity.kt` wholesale.
4. Keep live Google Sheets unchanged until reviewed mapping and write authorization.
5. Run unit tests and assembleDebug on the exact integration head; inspect runtime navigation and data persistence before merging.
6. Do not merge the draft PR into main without explicit approval.

## Remaining work
- Source-level diff for every overlapping Kotlin file.
- Integrate brokerage UI and Pune intelligence UI, with functional tests.
- Complete Master Projects persistence, selection, and project-first workflow.
- Complete broker multi-select group-sharing UI.
- Confirm successful CI and installable debug APK from current head.
