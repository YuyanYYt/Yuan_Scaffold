package dev.yuanscaffold.platform.generation;

import dev.yuanscaffold.platform.security.AuthContext;
import dev.yuanscaffold.platform.security.TenantPrincipal;
import dev.yuanscaffold.platform.store.PlatformStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class CodeExportAuditService {
    private final PlatformStore store;
    private final ObjectMapper mapper;

    public CodeExportAuditService(PlatformStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    /** Hash the exact yuan-manifest.json bytes written to the ZIP; only the digest is stored. */
    public String manifestSha256(JsonNode manifest) {
        try {
            byte[] json = (mapper.writeValueAsString(manifest) + "\n").getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID exportId, String manifestSha256, Outcome outcome, String errorCode) {
        TenantPrincipal actor = AuthContext.current();
        store.insertSourceExportAudit(actor.tenantId(), actor.userId(), exportId,
                manifestSha256, outcome.name(), errorCode);
    }

    public enum Outcome { SUCCESS, REJECTED, FAILED }
}
