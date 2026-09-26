CREATE TABLE app_users (
    id VARCHAR(36) PRIMARY KEY,
    login VARCHAR(80) NOT NULL UNIQUE,
    tenant_slug VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    permissions VARCHAR(256) NOT NULL
);

CREATE TABLE assets (
    id VARCHAR(36) PRIMARY KEY,
    tenant_slug VARCHAR(64) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    quantity INTEGER NOT NULL,
    available BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_assets_tenant ON assets(tenant_slug);
CREATE UNIQUE INDEX uk_assets_sku_859bb90c ON assets(tenant_slug, sku);
