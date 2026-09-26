package dev.yuan.sample.asset.domain;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import dev.yuan.sample.asset.api.AssetRequest;
import dev.yuan.sample.asset.api.AssetResponse;

@Service
public class AssetService {
    private final AssetRepository repository;

    public AssetService(AssetRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public List<AssetResponse> list(String tenantSlug, int page, int size) {
        return repository.findAllByTenantSlug(tenantSlug, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(AssetResponse::from).getContent();
    }

    @Transactional(readOnly = true)
    public AssetResponse get(String tenantSlug, String id) {
        return AssetResponse.from(find(tenantSlug, id));
    }

    @Transactional
    public AssetResponse create(String tenantSlug, AssetRequest request) {
        return AssetResponse.from(repository.save(new Asset(tenantSlug, request)));
    }

    @Transactional
    public AssetResponse update(String tenantSlug, String id, AssetRequest request) {
        Asset entity = find(tenantSlug, id);
        entity.apply(request);
        return AssetResponse.from(entity);
    }

    @Transactional
    public void delete(String tenantSlug, String id) { repository.delete(find(tenantSlug, id)); }

    private Asset find(String tenantSlug, String id) {
        return repository.findByIdAndTenantSlug(id, tenantSlug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
