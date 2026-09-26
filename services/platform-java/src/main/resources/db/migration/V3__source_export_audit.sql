ALTER TABLE audit_events ADD COLUMN manifest_sha256 CHAR(64);
ALTER TABLE audit_events ADD COLUMN outcome VARCHAR(16);
ALTER TABLE audit_events ADD COLUMN error_code VARCHAR(80);

CREATE INDEX ix_audit_tenant_manifest_time
    ON audit_events (tenant_id, manifest_sha256, created_at DESC);
