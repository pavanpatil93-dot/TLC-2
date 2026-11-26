-- Staging only; apply after 004 and explicit review.
BEGIN;
CREATE POLICY master_approval_admin_insert ON tlc.master_publication_approvals FOR INSERT WITH CHECK (
 approved_by IN (SELECT a.id FROM tlc.accounts a JOIN tlc.account_roles r ON r.account_id=a.id WHERE a.auth_subject=current_setting('tlc.auth_subject',true) AND a.approval_state='active' AND r.role='admin'));
CREATE POLICY master_approval_admin_update ON tlc.master_publication_approvals FOR UPDATE USING (
 EXISTS (SELECT 1 FROM tlc.accounts a JOIN tlc.account_roles r ON r.account_id=a.id WHERE a.auth_subject=current_setting('tlc.auth_subject',true) AND a.approval_state='active' AND r.role='admin'))
 WITH CHECK (EXISTS (SELECT 1 FROM tlc.accounts a JOIN tlc.account_roles r ON r.account_id=a.id WHERE a.auth_subject=current_setting('tlc.auth_subject',true) AND a.approval_state='active' AND r.role='admin'));
CREATE POLICY master_events_admin_insert ON tlc.master_publication_events FOR INSERT WITH CHECK (
 actor_id IN (SELECT a.id FROM tlc.accounts a JOIN tlc.account_roles r ON r.account_id=a.id WHERE a.auth_subject=current_setting('tlc.auth_subject',true) AND a.approval_state='active' AND r.role='admin'));
-- Restricted runtime grants must be provisioned separately; no DELETE or public access.
COMMIT;
