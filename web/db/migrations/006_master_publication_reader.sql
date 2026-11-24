-- STAGING ONLY. Provision a dedicated non-owner NOBYPASSRLS login named tlc_publication_reader separately.
BEGIN;
GRANT USAGE ON SCHEMA tlc TO tlc_publication_reader;
GRANT SELECT(property_key,payload_sha256,expires_at,revoked_at) ON tlc.master_publication_approvals TO tlc_publication_reader;
CREATE POLICY master_publication_reader ON tlc.master_publication_approvals FOR SELECT TO tlc_publication_reader USING (revoked_at IS NULL AND expires_at>now());
COMMIT;
