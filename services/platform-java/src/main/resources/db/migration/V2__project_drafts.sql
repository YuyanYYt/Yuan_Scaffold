CREATE TABLE project_drafts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(120) NOT NULL,
    manifest_json TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_project_drafts_revision CHECK (revision >= 1),
    CONSTRAINT uq_project_drafts_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_project_drafts_creator FOREIGN KEY (tenant_id, created_by)
        REFERENCES platform_users (tenant_id, id),
    CONSTRAINT fk_project_drafts_updater FOREIGN KEY (tenant_id, updated_by)
        REFERENCES platform_users (tenant_id, id)
);

CREATE INDEX ix_project_drafts_tenant_updated ON project_drafts (tenant_id, updated_at DESC, id DESC);
