-- Batch 05: restricted identity lookup for trusted server connections.
DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='tlc_web_runtime') THEN CREATE ROLE tlc_web_runtime NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB; END IF; END $$;
GRANT USAGE ON SCHEMA tlc TO tlc_web_runtime;
GRANT SELECT ON tlc.accounts, tlc.account_roles TO tlc_web_runtime;
CREATE POLICY batch05_account_self ON tlc.accounts FOR SELECT TO tlc_web_runtime USING (auth_subject = nullif(current_setting('tlc.auth_subject',true),'') AND approval_state='active');
CREATE POLICY batch05_roles_self ON tlc.account_roles FOR SELECT TO tlc_web_runtime USING (EXISTS (SELECT 1 FROM tlc.accounts a WHERE a.id=account_id AND a.auth_subject=nullif(current_setting('tlc.auth_subject',true),'') AND a.approval_state='active'));
-- No grants to anon/authenticated. All other tables remain deny-by-default.
