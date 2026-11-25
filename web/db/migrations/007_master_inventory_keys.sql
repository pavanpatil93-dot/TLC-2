-- Development migration. Preserve legacy records and audit history, but revoke legacy approvals.
BEGIN;
UPDATE tlc.master_publication_approvals
SET revoked_at=now(),version=version+1
WHERE property_key ~ '^(Commercial Inventory|Residential Inventory):'
AND revoked_at IS NULL;
-- Preserve historical keys only when revoked. New approvals require authoritative tab keys.
ALTER TABLE tlc.master_publication_approvals
DROP CONSTRAINT master_publication_approvals_property_key_check;
ALTER TABLE tlc.master_publication_approvals
ADD CONSTRAINT master_publication_approvals_property_key_check CHECK (
 property_key ~ '^(Commercial Lease Inventory|Commercial Resale Inventory|Residential Under Construction|Residential Resale|Residential Rentals):[A-Za-z0-9_-]{1,64}$'
 OR (revoked_at IS NOT NULL AND property_key ~ '^(Commercial Inventory|Residential Inventory):[A-Za-z0-9_-]{1,64}$')
);
COMMIT;
