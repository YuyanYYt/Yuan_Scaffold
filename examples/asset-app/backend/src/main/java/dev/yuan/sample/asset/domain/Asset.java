package dev.yuan.sample.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import dev.yuan.sample.asset.api.AssetRequest;

@Entity
@Table(name = "assets")
public class Asset {
    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "tenant_slug", nullable = false, length = 64)
    private String tenantSlug;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "available", nullable = false)
    private Boolean available;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Asset() { }

    public Asset(String tenantSlug, AssetRequest request) {
        this.id = UUID.randomUUID().toString();
        this.tenantSlug = tenantSlug;
        this.createdAt = Instant.now();
        apply(request);
    }

    public void apply(AssetRequest request) {
        this.sku = request.sku();
        this.name = request.name();
        this.quantity = request.quantity();
        this.available = request.available();
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTenantSlug() { return tenantSlug; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public Integer getQuantity() { return quantity; }
    public Boolean getAvailable() { return available; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
