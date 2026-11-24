-- Batch 04 staging: self-profile role reads ONLY.
-- Requires migration 001 and DBA-created LOGIN role tlc_web_runtime.
-- The restricted role MUST NOT own these tables, be superuser, or have BYPASSRLS.
-- Connection URL is supplied in staging Vercel Preview only. No credential in Git.
BEGIN;
GRANT USAGE ON SCHEMA tlc TO tlc_web_runtime;
GRANT SELECT ON tlc.accounts,tlc.account_roles TO tlc_web_runtime;
-- A request-scoped, transaction-local auth subject is set ONLY after GoTrue
-- verifies a real access token via /auth/v1/user.
CREATE POLICY tlc_self_profile_read ON tlc.accounts
  FOR SELECT TO tlc_web_runtime
  USING (approval_state='active' AND auth_subject = nullif(current_setting('tlc.auth_subject',true),''));
CREATE POLICY tlc_self_roles_read ON tlc.account_roles
  FOR SELECT TO tlc_web_runtime
  USING (EXISTS (
   SELECT 1 FROM tlc.accounts a
   WHERE a.id=account_id AND a.approval_state='active'
     AND a.auth_subject=nullif(current_setting('tlc.auth_subject',true),'')
  ));
-- No insert/update/delete grants, no public grants and no write policies.
-- Owner/broker/client accounts require approval & manual role provisioning in staging.
COMMIT;
