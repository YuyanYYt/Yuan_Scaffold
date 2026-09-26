package dev.yuan.sample.asset.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import dev.yuan.sample.asset.domain.AssetService;
import dev.yuan.sample.asset.security.TenantPrincipal;

@RestController
@Validated
@RequestMapping("/api/assets")
public class AssetController {
    private final AssetService service;

    public AssetController(AssetService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAuthority('asset:read')")
    public List<AssetResponse> list(@AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(principal.tenantSlug(), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('asset:read')")
    public AssetResponse get(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable String id) {
        return service.get(principal.tenantSlug(), id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('asset:write')")
    public ResponseEntity<AssetResponse> create(@AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody AssetRequest request) {
        AssetResponse created = service.create(principal.tenantSlug(), request);
        return ResponseEntity.created(URI.create("/api/assets/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('asset:write')")
    public AssetResponse update(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable String id,
            @Valid @RequestBody AssetRequest request) {
        return service.update(principal.tenantSlug(), id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('asset:write')")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable String id) {
        service.delete(principal.tenantSlug(), id);
        return ResponseEntity.noContent().build();
    }
}
