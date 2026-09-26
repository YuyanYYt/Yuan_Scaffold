package dev.yuan.sample.asset.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepository extends JpaRepository<Asset, String> {
    Page<Asset> findAllByTenantSlug(String tenantSlug, Pageable pageable);
    Optional<Asset> findByIdAndTenantSlug(String id, String tenantSlug);
}
