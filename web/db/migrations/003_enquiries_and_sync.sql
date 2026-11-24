-- Batch 07/09, STAGING ONLY. Run after 001_core.sql and 002_verified_access.sql.
-- No automatic data imports or public inventory changes.
BEGIN;
CREATE TABLE IF NOT EXISTS tlc.enquiries(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 contact_name text NOT NULL CHECK(length(trim(contact_name)) BETWEEN 2 AND 120),
 contact_phone_e164 text NOT NULL CHECK(contact_phone_e164 ~ '^\+[1-9][0-9]{7,14}$'),
 property_public_ref text CHECK(length(property_public_ref)<=150),
 requested_location text CHECK(length(requested_location)<=160),
 requirement text NOT NULL CHECK(length(trim(requirement)) BETWEEN 5 AND 2000),
 consent_at timestamptz NOT NULL,
 source text NOT NULL DEFAULT 'public_web' CHECK(source IN ('public_web','approved_partner')),
 status text NOT NULL DEFAULT 'new' CHECK(status IN ('new','assigned','follow_up','closed','spam')),
 assigned_to uuid REFERENCES tlc.accounts(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS enquiry_created_idx ON tlc.enquiries(created_at DESC);
ALTER TABLE tlc.enquiries ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.enquiries FORCE ROW LEVEL SECURITY;
REVOKE ALL ON tlc.enquiries FROM PUBLIC;
-- Server must verify Cloudflare Turnstile BEFORE setting this transaction-local claim.
CREATE POLICY enquiry_captcha_verified_insert ON tlc.enquiries FOR INSERT WITH CHECK(
 current_setting('tlc.public_enquiry_verified',true)='true'
 AND source='public_web' AND status='new' AND assigned_to IS NULL
);
CREATE POLICY enquiry_staff_read ON tlc.enquiries FOR SELECT USING(
 EXISTS(SELECT 1 FROM tlc.account_roles r JOIN tlc.accounts a ON a.id=r.account_id
 WHERE a.auth_subject=current_setting('tlc.auth_subject',true) AND a.approval_state='active'
 AND r.role IN('admin','commercial_manager','residential_manager','sales_executive')));
CREATE POLICY outbox_staff_read ON tlc.change_outbox FOR SELECT USING(
 EXISTS(SELECT 1 FROM tlc.account_roles r JOIN tlc.accounts a ON a.id=r.account_id
 WHERE a.auth_subject=current_setting('tlc.auth_subject',true) AND a.approval_state='active'
 AND r.role IN('admin','commercial_manager','residential_manager','inventory_manager','sales_executive','read_only'))
);
-- Restricted service role grants are separate from RLS:
-- GRANT INSERT,SELECT ON tlc.enquiries TO tlc_web_runtime;
-- GRANT SELECT ON tlc.change_outbox TO tlc_web_runtime;
-- GRANT USAGE ON ALL SEQUENCES IN SCHEMA tlc TO tlc_web_runtime;
COMMIT;
