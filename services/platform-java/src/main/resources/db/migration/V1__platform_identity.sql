CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    slug VARCHAR(63) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE platform_users (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    username VARCHAR(63) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_platform_users_tenant_username UNIQUE (tenant_id, username),
    CONSTRAINT uq_platform_users_tenant_id UNIQUE (tenant_id, id)
);

CREATE TABLE roles (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(63) NOT NULL,
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_roles_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_roles_tenant_id UNIQUE (tenant_id, id)
);

CREATE TABLE menus (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(63) NOT NULL,
    title VARCHAR(120) NOT NULL,
    permission_code VARCHAR(80) NOT NULL,
    path VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_menus_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_menus_tenant_id UNIQUE (tenant_id, id)
);

CREATE TABLE user_roles (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform_users (tenant_id, id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (tenant_id, role_id)
        REFERENCES roles (tenant_id, id)
);

CREATE TABLE role_menus (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    role_id UUID NOT NULL,
    menu_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, role_id, menu_id),
    CONSTRAINT fk_role_menus_role FOREIGN KEY (tenant_id, role_id)
        REFERENCES roles (tenant_id, id),
    CONSTRAINT fk_role_menus_menu FOREIGN KEY (tenant_id, menu_id)
        REFERENCES menus (tenant_id, id)
);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    actor_user_id UUID NOT NULL,
    action VARCHAR(80) NOT NULL,
    object_type VARCHAR(40) NOT NULL,
    object_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_actor FOREIGN KEY (tenant_id, actor_user_id)
        REFERENCES platform_users (tenant_id, id)
);

CREATE INDEX ix_platform_users_tenant ON platform_users (tenant_id);
CREATE INDEX ix_roles_tenant ON roles (tenant_id);
CREATE INDEX ix_menus_tenant ON menus (tenant_id);
CREATE INDEX ix_audit_tenant_time ON audit_events (tenant_id, created_at DESC);
