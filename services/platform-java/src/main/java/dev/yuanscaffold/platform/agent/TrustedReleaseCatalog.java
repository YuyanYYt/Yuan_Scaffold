package dev.yuanscaffold.platform.agent;

import dev.yuanscaffold.platform.security.TenantPrincipal;
import java.util.UUID;

/** Implement only with a server-owned release record, thread ACL and authoritative grants. */
public interface TrustedReleaseCatalog {
    ReleaseAuthorization authorize(TenantPrincipal principal, WorkflowRef requested,
                                   UUID runId, UUID threadId, Operation operation);

    enum Operation { START, STATUS, RESUME }
}
