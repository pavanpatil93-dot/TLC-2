-- TLC-2 Batches 06–09 — staging-only extension, not a production migration.
-- Prerequisite: 001_core.sql. Execute ONLY following DBA review and safe backup.
-- Every new table is FORCE RLS with no permissive policy. NEVER apply to live Sheets.
BEGIN;
CREATE TABLE IF NOT EXISTS tlc.property_enquiries(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 property_id uuid REFERENCES tlc.properties(id) ON DELETE RESTRICT,
 project_id uuid REFERENCES tlc.projects(id) ON DELETE RESTRICT,
 client_account_id uuid REFERENCES tlc.accounts(id) ON DELETE RESTRICT,
 client_name text NOT NULL,
 client_phone_e164 text,
 client_email text,
 enquiry_kind text NOT NULL CHECK(enquiry_kind IN ('details','site_visit','shortlist','other')),
 contact_consent boolean NOT NULL DEFAULT false,
 enquiry_state text NOT NULL DEFAULT 'new' CHECK(enquiry_state IN ('new','assigned','contacted','site_visit_planned','closed')),
 assigned_to uuid REFERENCES tlc.accounts(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(property_id IS NOT NULL OR project_id IS NOT NULL),
 CHECK(contact_consent=true)
);
CREATE INDEX IF NOT EXISTS enquiry_assignee_idx ON tlc.property_enquiries(assigned_to,enquiry_state,created_at DESC);
CREATE TABLE IF NOT EXISTS tlc.sync_devices(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 account_id uuid NOT NULL REFERENCES tlc.accounts(id) ON DELETE RESTRICT,
 device_public_id text NOT NULL,
 last_acknowledged_event_id bigint NOT NULL DEFAULT 0 CHECK(last_acknowledged_event_id>=0),
 disabled_at timestamptz,
 last_seen_at timestamptz,
 UNIQUE(account_id,device_public_id)
);
CREATE TABLE IF NOT EXISTS tlc.import_runs(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 source_system text NOT NULL,
 source_fingerprint text NOT NULL,
 status text NOT NULL DEFAULT 'dry_run' CHECK(status IN ('dry_run','approved','running','completed','failed','rolled_back')),
 total_rows integer NOT NULL DEFAULT 0 CHECK(total_rows>=0),
 valid_rows integer NOT NULL DEFAULT 0 CHECK(valid_rows>=0),
 duplicate_rows integer NOT NULL DEFAULT 0 CHECK(duplicate_rows>=0),
 created_by uuid REFERENCES tlc.accounts(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz,
 UNIQUE(source_system,source_fingerprint)
);
CREATE TABLE IF NOT EXISTS tlc.import_conflicts(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 run_id uuid NOT NULL REFERENCES tlc.import_runs(id) ON DELETE RESTRICT,
 legacy_row_key text NOT NULL,
 reason text NOT NULL,
 proposed_values jsonb NOT NULL,
 resolution text CHECK(resolution IN ('skip','retain_existing','manual_merge','approved_insert')),
 resolved_by uuid REFERENCES tlc.accounts(id) ON DELETE RESTRICT,
 resolved_at timestamptz,
 UNIQUE(run_id,legacy_row_key,reason)
);
CREATE INDEX IF NOT EXISTS import_conflicts_run_idx ON tlc.import_conflicts(run_id);
ALTER TABLE tlc.property_enquiries ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.property_enquiries FORCE ROW LEVEL SECURITY;
ALTER TABLE tlc.sync_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.sync_devices FORCE ROW LEVEL SECURITY;
ALTER TABLE tlc.import_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.import_runs FORCE ROW LEVEL SECURITY;
ALTER TABLE tlc.import_conflicts ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.import_conflicts FORCE ROW LEVEL SECURITY;
REVOKE ALL ON ALL TABLES IN SCHEMA tlc FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA tlc FROM PUBLIC;
COMMIT;
