-- Batch 03 DBA review checks; execute in staging as migration owner.
-- Expected values for each query are annotated.
SELECT count(*) AS expected_10_tables FROM information_schema.tables WHERE table_schema='tlc' AND table_type='BASE TABLE';
-- Expected: 10 (accounts, account_roles, parties, projects, properties,
-- legacy_identifiers, property_submissions, property_media,
-- audit_events, change_outbox, idempotency_keys) => 11; see count below.
SELECT tablename, rowsecurity FROM pg_tables WHERE schemaname='tlc' ORDER BY tablename;
-- All 11 tables: rowsecurity must be true.
SELECT relname, relforcerowsecurity FROM pg_class c
 JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='tlc' AND relkind='r' ORDER BY relname;
-- All table rows: relforcerowsecurity must be true.
SELECT count(*) AS policy_count FROM pg_policies WHERE schemaname='tlc';
-- Expected: 0 (fail-closed until secured RLS policies ship).
