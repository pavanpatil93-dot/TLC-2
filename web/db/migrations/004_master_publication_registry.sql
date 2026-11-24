-- STAGING ONLY. Requires reviewed runtime grants. No public read policy.
BEGIN;
CREATE TABLE tlc.master_publication_approvals (
 property_key text PRIMARY KEY CHECK(property_key ~ '^(Commercial Inventory|Residential Inventory):[A-Za-z0-9_-]{1,64}$'),
 evidence_reference text NOT NULL CHECK(length(btrim(evidence_reference)) BETWEEN 1 AND 500),
 payload_sha256 text NOT NULL CHECK(payload_sha256 ~ '^[a-f0-9]{64}$'),
 approved_by uuid NOT NULL REFERENCES tlc.accounts(id),
 approved_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL,
 revoked_at timestamptz,
 version integer NOT NULL DEFAULT 1 CHECK(version>0),
 CHECK(expires_at>approved_at AND expires_at<=approved_at+interval '90 days')
);
CREATE TABLE tlc.master_publication_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 property_key text NOT NULL REFERENCES tlc.master_publication_approvals(property_key),
 actor_id uuid NOT NULL REFERENCES tlc.accounts(id),
 action text NOT NULL CHECK(action IN ('approve','revoke')),
 version integer NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE tlc.master_publication_approvals ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.master_publication_approvals FORCE ROW LEVEL SECURITY;
ALTER TABLE tlc.master_publication_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE tlc.master_publication_events FORCE ROW LEVEL SECURITY;
REVOKE ALL ON tlc.master_publication_approvals,tlc.master_publication_events FROM PUBLIC;
CREATE POLICY master_approval_admin_read ON tlc.master_publication_approvals FOR SELECT USING (
 EXISTS(SELECT 1 FROM tlc.accounts a JOIN tlc.account_roles r ON r.account_id=a.id WHERE a.auth_subject=current_setting('tlc.auth_subject',true) AND a.approval_state='active' AND r.role='admin'));
CREATE POLICY master_events_admin_read ON tlc.master_publication_events FOR SELECT USING (
 EXISTS(SELECT 1 FROM tlc.accounts a JOIN tlc.account_roles r ON r.account_id=a.id WHERE a.auth_subject=current_setting('tlc.auth_subject',true) AND a.approval_state='active' AND r.role='admin'));
-- No write policies or grants until owner evidence verification and transactional audit are reviewed.
COMMIT;
