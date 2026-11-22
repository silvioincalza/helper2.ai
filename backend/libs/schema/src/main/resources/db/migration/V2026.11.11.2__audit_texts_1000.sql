-- UC-013 BR-004: the value before, the value after and the reason of an audit entry hold up to
-- 1000 characters each, so an address of 300 characters (UC-020 BR-001) fits a value.
-- IF EXISTS: staging deploys main, so the script gives the same schema when it runs again.
ALTER TABLE admin_audit DROP CONSTRAINT IF EXISTS admin_audit_before_value_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_before_value_check
    CHECK (before_value IS NULL OR length(before_value) <= 1000);
ALTER TABLE admin_audit DROP CONSTRAINT IF EXISTS admin_audit_after_value_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_after_value_check
    CHECK (after_value IS NULL OR length(after_value) <= 1000);
ALTER TABLE admin_audit DROP CONSTRAINT IF EXISTS admin_audit_reason_check;
ALTER TABLE admin_audit ADD CONSTRAINT admin_audit_reason_check
    CHECK (reason IS NULL OR length(reason) <= 1000);
