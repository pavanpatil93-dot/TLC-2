-- TLC staging: add Tenant as an explicit optional onboarding and approved account kind.
-- Never convert self-reported auth metadata into an approved TLC database role.
BEGIN;
ALTER TABLE tlc.accounts DROP CONSTRAINT IF EXISTS accounts_account_kind_check;
ALTER TABLE tlc.accounts ADD CONSTRAINT accounts_account_kind_check
  CHECK(account_kind IN ('employee','owner','developer','broker','client','tenant'));
ALTER TABLE tlc.account_roles DROP CONSTRAINT IF EXISTS account_roles_role_check;
ALTER TABLE tlc.account_roles ADD CONSTRAINT account_roles_role_check
  CHECK(role IN ('admin','commercial_manager','residential_manager','inventory_manager','sales_executive','read_only','owner','developer','broker','client','tenant'));
COMMIT;
