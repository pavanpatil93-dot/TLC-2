-- Batch 04-06, STAGING ONLY. Run after 001_core.sql and verified identity provisioning.
-- This creates the policies for a restricted server-side runtime role; it creates no user,
-- does not import data, does not allow public reads, and does not grant publication rights.
BEGIN;
CREATE POLICY account_self_read ON tlc.accounts FOR SELECT USING (
 auth_subject IS NOT NULL AND auth_subject=current_setting('tlc.auth_subject',true)
 AND approval_state='active');
CREATE POLICY account_role_self_read ON tlc.account_roles FOR SELECT USING (
 account_id IN (SELECT id FROM tlc.accounts WHERE auth_subject=current_setting('tlc.auth_subject',true) AND approval_state='active'));
CREATE POLICY project_staff_read ON tlc.projects FOR SELECT USING (
 EXISTS (SELECT 1 FROM tlc.account_roles r JOIN tlc.accounts a ON a.id=r.account_id
 WHERE a.auth_subject=current_setting('tlc.auth_subject',true) AND a.approval_state='active'
 AND r.role IN ('admin','commercial_manager','residential_manager','inventory_manager','sales_executive','read_only')));
CREATE POLICY inventory_staff_read ON tlc.properties FOR SELECT USING (
 EXISTS (SELECT 1 FROM tlc.account_roles r JOIN tlc.accounts a ON a.id=r.account_id
 WHERE a.auth_subject=current_setting('tlc.auth_subject',true) AND a.approval_state='active'
 AND r.role IN ('admin','commercial_manager','residential_manager','inventory_manager','sales_executive','read_only')));
CREATE POLICY inventory_staff_create_draft ON tlc.properties FOR INSERT WITH CHECK (
 publication_status='draft' AND verification_status='pending'
 AND availability_status='not_confirmed'
 AND source_account_id IN (SELECT id FROM tlc.accounts WHERE auth_subject=current_setting('tlc.auth_subject',true))
 AND EXISTS (SELECT 1 FROM tlc.account_roles r JOIN tlc.accounts a ON a.id=r.account_id
 WHERE a.auth_subject=current_setting('tlc.auth_subject',true) AND a.approval_state='active'
 AND r.role IN ('admin','commercial_manager','residential_manager','inventory_manager','sales_executive')));
CREATE POLICY inventory_staff_edit_unpublished ON tlc.properties FOR UPDATE USING (
 publication_status IN ('draft','pending_review','approved','unpublished')
 AND EXISTS (SELECT 1 FROM tlc.account_roles r JOIN tlc.accounts a ON a.id=r.account_id
 WHERE a.auth_subject=current_setting('tlc.auth_subject',true) AND a.approval_state='active'
 AND r.role IN ('admin','commercial_manager','residential_manager','inventory_manager'))
) WITH CHECK(publication_status IN ('draft','pending_review','approved','unpublished','archived'));
CREATE POLICY submission_submitter_read ON tlc.property_submissions FOR SELECT USING(
 submitted_by IN(SELECT id FROM tlc.accounts WHERE auth_subject=current_setting('tlc.auth_subject',true))
 OR EXISTS(SELECT 1 FROM tlc.account_roles r JOIN tlc.accounts a ON a.id=r.account_id
 WHERE a.auth_subject=current_setting('tlc.auth_subject',true) AND a.approval_state='active'
 AND r.role IN('admin','commercial_manager','residential_manager','inventory_manager')));
CREATE POLICY submission_submitter_create ON tlc.property_submissions FOR INSERT WITH CHECK(
 state IN ('draft','submitted')
 AND submitted_by IN(SELECT id FROM tlc.accounts WHERE auth_subject=current_setting('tlc.auth_subject',true))
 AND EXISTS(SELECT 1 FROM tlc.account_roles r JOIN tlc.accounts a ON a.id=r.account_id
 WHERE a.auth_subject=current_setting('tlc.auth_subject',true) AND a.approval_state='active'
 AND r.role IN('admin','commercial_manager','residential_manager','inventory_manager','sales_executive','owner','broker','developer')));
CREATE POLICY audit_actor_insert ON tlc.audit_events FOR INSERT WITH CHECK(
 actor_account_id IN(SELECT id FROM tlc.accounts WHERE auth_subject=current_setting('tlc.auth_subject',true)));
CREATE POLICY outbox_staff_insert ON tlc.change_outbox FOR INSERT WITH CHECK (
 EXISTS(SELECT 1 FROM tlc.account_roles r JOIN tlc.accounts a ON a.id=r.account_id
 WHERE a.auth_subject=current_setting('tlc.auth_subject',true) AND a.approval_state='active'
 AND r.role IN('admin','commercial_manager','residential_manager','inventory_manager','sales_executive')));
-- No SELECT on audit, parties or outbox; no DELETE on any business table.
-- These are application SQL grants, separate from RLS; create the restricted role outside migration:
-- GRANT USAGE ON SCHEMA tlc TO tlc_web_runtime;
-- GRANT SELECT ON tlc.accounts,tlc.account_roles,tlc.projects,tlc.properties,tlc.property_submissions TO tlc_web_runtime;
-- GRANT INSERT ON tlc.properties,tlc.property_submissions,tlc.audit_events,tlc.change_outbox TO tlc_web_runtime;
-- GRANT UPDATE ON tlc.properties TO tlc_web_runtime;
-- GRANT USAGE ON ALL SEQUENCES IN SCHEMA tlc TO tlc_web_runtime;
COMMIT;
