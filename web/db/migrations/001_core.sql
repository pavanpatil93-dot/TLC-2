-- TLC-2 Batch 03 / PostgreSQL 15+ foundation
-- APPLY ONLY TO A NEW EMPTY STAGING DATABASE AFTER REVIEW.
-- Does NOT modify Google Sheets, Android data, or production.
-- Roles and credentials are provisioned separately (never checked into Git).
BEGIN;

CREATE SCHEMA IF NOT EXISTS tlc;
REVOKE ALL ON SCHEMA tlc FROM PUBLIC;

-- Account identity will be bound to a verified auth subject in Batch 04.
CREATE TABLE IF NOT EXISTS tlc.accounts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  auth_subject text UNIQUE,
  account_kind text NOT NULL CHECK(account_kind IN ('employee','owner','developer','broker','client')),
  display_name text NOT NULL,
  email text,
  phone_e164 text,
  approval_state text NOT NULL DEFAULT 'pending' CHECK(approval_state IN ('pending','active','suspended','rejected')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS tlc.account_roles (
  account_id uuid NOT NULL REFERENCES tlc.accounts(id) ON DELETE RESTRICT,
  role text NOT NULL CHECK(role IN ('admin','commercial_manager','residential_manager','inventory_manager','sales_executive','read_only','owner','developer','broker','client')),
  PRIMARY KEY(account_id,role)
);

-- Legal parties (owner / broker / developer) are ALWAYS private data.
CREATE TABLE IF NOT EXISTS tlc.parties (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  party_type text NOT NULL CHECK(party_type IN ('owner','broker','developer','other')),
  name text,
  phone_e164 text,
  email text,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS tlc.projects (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  project_code text UNIQUE,
  name text NOT NULL,
  location text NOT NULL,
  micro_market text,
  business_segment text NOT NULL CHECK(business_segment IN ('commercial','residential')),
  construction_status text CHECK(construction_status IN ('unknown','under_construction','ready_to_move','completed','on_hold')),
  possession_date date,
  possession_precision text CHECK(possession_precision IN ('exact','month','quarter','year','unknown')),
  project_origin text CHECK(project_origin IN ('unknown','direct_developer','resale','mixed')),
  developer_party_id uuid REFERENCES tlc.parties(id) ON DELETE RESTRICT,
  verification_status text NOT NULL DEFAULT 'pending' CHECK(verification_status IN ('pending','verified','rejected')),
  publication_status text NOT NULL DEFAULT 'draft' CHECK(publication_status IN ('draft','pending_review','approved','published','unpublished','archived')),
  public_approved_at timestamptz,
  archived_at timestamptz,
  version bigint NOT NULL DEFAULT 1 CHECK(version>0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT project_published_guard CHECK(publication_status <> 'published' OR (verification_status='verified' AND public_approved_at IS NOT NULL AND archived_at IS NULL))
);
CREATE INDEX IF NOT EXISTS projects_location_idx ON tlc.projects (lower(location));
CREATE INDEX IF NOT EXISTS projects_publication_idx ON tlc.projects (publication_status,business_segment);

-- One logical property/unit has exactly one UUID, regardless of sales channels.
-- Legacy codes remain aliases: never overwrite a known historic ID.
CREATE TABLE IF NOT EXISTS tlc.properties (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id uuid REFERENCES tlc.projects(id) ON DELETE RESTRICT,
  property_code text UNIQUE,
  unit_number text,
  floor_label text,
  business_segment text NOT NULL CHECK(business_segment IN ('commercial','residential')),
  asset_type text NOT NULL CHECK(asset_type IN ('office','shop','showroom','warehouse','commercial_land','apartment','villa','bungalow','residential_plot','other')),
  transaction_type text NOT NULL CHECK(transaction_type IN ('lease','sale','pre_leased_sale')),
  carpet_sqft numeric(14,2) CHECK(carpet_sqft>0),
  builtup_sqft numeric(14,2) CHECK(builtup_sqft>0),
  asking_rent_inr numeric(16,2) CHECK(asking_rent_inr>=0),
  asking_price_inr numeric(18,2) CHECK(asking_price_inr>=0),
  current_rent_inr numeric(16,2) CHECK(current_rent_inr>=0),
  furnishing text,
  possession_date date,
  owner_party_id uuid REFERENCES tlc.parties(id) ON DELETE RESTRICT,
  source_account_id uuid REFERENCES tlc.accounts(id) ON DELETE RESTRICT,
  availability_status text NOT NULL DEFAULT 'not_confirmed' CHECK(availability_status IN ('available','not_available','not_confirmed','on_hold','under_negotiation','token_received','sold','leased','withdrawn','verification_pending')),
  verification_status text NOT NULL DEFAULT 'pending' CHECK(verification_status IN ('pending','verified','rejected')),
  publication_status text NOT NULL DEFAULT 'draft' CHECK(publication_status IN ('draft','pending_review','approved','published','unpublished','archived')),
  public_approved_by uuid REFERENCES tlc.accounts(id) ON DELETE RESTRICT,
  public_approved_at timestamptz,
  published_at timestamptz,
  archived_at timestamptz,
  version bigint NOT NULL DEFAULT 1 CHECK(version>0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT public_property_guard CHECK(publication_status <> 'published' OR (
    verification_status='verified' AND availability_status='available'
    AND public_approved_by IS NOT NULL AND public_approved_at IS NOT NULL
    AND archived_at IS NULL))
);
CREATE INDEX IF NOT EXISTS properties_project_idx ON tlc.properties(project_id);
CREATE INDEX IF NOT EXISTS properties_public_idx ON tlc.properties (publication_status,availability_status,business_segment);
CREATE INDEX IF NOT EXISTS properties_updated_idx ON tlc.properties(updated_at);
CREATE UNIQUE INDEX IF NOT EXISTS property_project_unit_transaction_unique
  ON tlc.properties (project_id,lower(btrim(unit_number)),transaction_type)
  WHERE project_id IS NOT NULL AND unit_number IS NOT NULL AND btrim(unit_number)<>'';

CREATE TABLE IF NOT EXISTS tlc.legacy_identifiers (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  property_id uuid NOT NULL REFERENCES tlc.properties(id) ON DELETE RESTRICT,
  source_system text NOT NULL,
  source_tab text NOT NULL DEFAULT '',
  legacy_id text NOT NULL,
  imported_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(source_system,source_tab,legacy_id)
);

-- Staged submissions are not live records. These may include private fields.
CREATE TABLE IF NOT EXISTS tlc.property_submissions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  submitted_by uuid REFERENCES tlc.accounts(id) ON DELETE RESTRICT,
  property_id uuid REFERENCES tlc.properties(id) ON DELETE RESTRICT,
  proposed_values jsonb NOT NULL,
  state text NOT NULL DEFAULT 'draft' CHECK(state IN ('draft','submitted','under_review','approved','rejected','returned')),
  reviewed_by uuid REFERENCES tlc.accounts(id) ON DELETE RESTRICT,
  reviewed_at timestamptz,
  review_reason text,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Private storage objects by default; a media record is not automatically public.
CREATE TABLE IF NOT EXISTS tlc.property_media (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  property_id uuid REFERENCES tlc.properties(id) ON DELETE RESTRICT,
  project_id uuid REFERENCES tlc.projects(id) ON DELETE RESTRICT,
  storage_key text NOT NULL UNIQUE,
  media_type text NOT NULL CHECK(media_type IN ('photo','floor_plan','document','video')),
  visibility text NOT NULL DEFAULT 'private' CHECK(visibility IN ('private','approved_public')),
  approved_by uuid REFERENCES tlc.accounts(id) ON DELETE RESTRICT,
  approved_at timestamptz,
  sort_order integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK(property_id IS NOT NULL OR project_id IS NOT NULL),
  CHECK(visibility<>'approved_public' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS tlc.audit_events (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_account_id uuid REFERENCES tlc.accounts(id) ON DELETE RESTRICT,
  action text NOT NULL,
  entity_type text NOT NULL,
  entity_id uuid NOT NULL,
  previous_version bigint,
  next_version bigint,
  diff jsonb NOT NULL DEFAULT '{}'::jsonb,
  request_id text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS audit_entity_idx ON tlc.audit_events(entity_type,entity_id,created_at DESC);

-- Durable outbox supports Android / website change notification and retries.
CREATE TABLE IF NOT EXISTS tlc.change_outbox (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  entity_type text NOT NULL,
  entity_id uuid NOT NULL,
  entity_version bigint NOT NULL,
  operation text NOT NULL CHECK(operation IN ('create','update','publish','unpublish','archive')),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  delivered_at timestamptz,
  attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS outbox_pending_idx ON tlc.change_outbox(id) WHERE delivered_at IS NULL;
CREATE TABLE IF NOT EXISTS tlc.idempotency_keys (
  scope text NOT NULL,
  key text NOT NULL,
  request_hash text NOT NULL,
  response_code integer,
  response_data jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  PRIMARY KEY(scope,key)
);

-- The service account is NOT authorized to disclose any base tables.
-- RLS is default DENY with no permissive policies in Batch 03.
ALTER TABLE tlc.accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.accounts FORCE ROW LEVEL SECURITY;
ALTER TABLE tlc.account_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.account_roles FORCE ROW LEVEL SECURITY;
ALTER TABLE tlc.parties ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.parties FORCE ROW LEVEL SECURITY;
ALTER TABLE tlc.projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.projects FORCE ROW LEVEL SECURITY;
ALTER TABLE tlc.properties ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.properties FORCE ROW LEVEL SECURITY;
ALTER TABLE tlc.legacy_identifiers ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.legacy_identifiers FORCE ROW LEVEL SECURITY;
ALTER TABLE tlc.property_submissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.property_submissions FORCE ROW LEVEL SECURITY;
ALTER TABLE tlc.property_media ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.property_media FORCE ROW LEVEL SECURITY;
ALTER TABLE tlc.audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.audit_events FORCE ROW LEVEL SECURITY;
ALTER TABLE tlc.change_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.change_outbox FORCE ROW LEVEL SECURITY;
ALTER TABLE tlc.idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.idempotency_keys FORCE ROW LEVEL SECURITY;

REVOKE ALL ON ALL TABLES IN SCHEMA tlc FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA tlc FROM PUBLIC;
COMMIT;
