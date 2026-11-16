# TLC-1 Pune Real Estate Intelligence — v2.0 seed

## Integrity model
- Verified-source records live in `commercial_projects.json`, `metro_stations.json`, and `postal_offices.json`. Verification is scoped to the field, not every possible detail.
- `legacy_enrichment_candidates.json` and `road_candidates.json` are **quarantined**, not authoritative; never display them as verified facts or import into production autocomplete without review.
- `locations.json` is a market-name seed. `pin_codes` intentionally empty until address-level verification.
- `landmarks_retail_hotels.json`, `market_observations.json`, and `transport_links.json` are empty rather than invented.
- `residential_projects.json` contains only directly sourced developer-name-level information; no invented pricing, possession or RERA IDs.
- Metro future projects are explicitly **not operational**.

## Android Room import
Copy JSON assets to `app/src/main/assets/pune_db/`. Parse into Room entities, upsert by stable `id`, and run `validate.py` before packaging. Do not delete user inventory. `schema.json` documents relations and type conversion.

## Validation
Run `python validate.py` in this folder.
