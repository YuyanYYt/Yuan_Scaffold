package dev.yuan.sample.asset.api;

import java.time.Instant;
import dev.yuan.sample.asset.domain.Asset;

public record AssetResponse(
        String id,
        String sku,
        String name,
        Integer quantity,
        Boolean available,
        Instant createdAt,
        Instant updatedAt
) {
    public static AssetResponse from(Asset entity) {
        return new AssetResponse(entity.getId(), entity.getSku(), entity.getName(), entity.getQuantity(), entity.getAvailable(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
