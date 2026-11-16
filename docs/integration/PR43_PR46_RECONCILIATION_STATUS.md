# TLC-1 PR #43 + PR #46 reconciliation status

## Branch
`integration/tlc1-reconcile-pr43-pr46-20261111` (draft PR #47; never merge without approval).

## Verified incorporated
- Base: current main `96371461`, retaining newer CRM, master project/Pune Intelligence database assets, 918-line brokerage calculator, and production app ID.
- PR #46 fix `47cc2e11`: ten numeric JSON flags decoded into Boolean fields in MasterDatabaseSeeder.
- PR #43: all 20 changed paths that do not overlap with files changed on main, including tests, navigation components, independent models, CRM Calls screen, map search, and integration docs.
- Existing live Google Sheets and user inventory untouched.

## Overlapping paths requiring manual reconciliation
1. `app/src/main/java/com/example/MainActivity.kt`
2. `app/src/main/java/com/example/data/SheetsRepository.kt`
3. `app/src/main/java/com/example/ui/components/AppNavigationDrawer.kt`
4. `app/src/main/java/com/example/ui/components/PropertyCard.kt`
5. `app/src/main/java/com/example/ui/components/WhatsAppShareDialog.kt`
6. `app/src/main/java/com/example/ui/screens/AddPropertyScreen.kt`
7. `app/src/main/java/com/example/ui/screens/BrokerageDealCalculator.kt`
8. `app/src/main/java/com/example/ui/screens/CrmClientProfile.kt`
9. `app/src/main/java/com/example/ui/screens/CrmFoundationScreen.kt`
10. `app/src/main/java/com/example/ui/screens/ExploreScreen.kt`
11. `app/src/main/java/com/example/ui/screens/PropertyDetailScreen.kt`
12. `app/src/main/java/com/example/ui/theme/Color.kt`
13. `app/src/main/java/com/example/ui/theme/Theme.kt`
14. `app/src/main/java/com/example/ui/theme/Type.kt`

Current policy is to retain main versions of these 14 files until feature-by-feature review; they are **not reconciled**. PR #43 features in those files may still be missing. A green CI is not sufficient to claim feature parity.

## Gates
- Run unit tests + debug APK compilation on the exact PR #47 head.
- Review and reconcile the 14 paths; rerun CI after every change.
- Validate data migration and CRM offline persistence on device.
- Only after all gates pass deliver a combined APK and request explicit approval before merging to main.
