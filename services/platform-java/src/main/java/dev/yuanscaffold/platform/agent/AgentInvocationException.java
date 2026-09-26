package dev.yuanscaffold.platform.agent;

/** Sanitized service-to-service failure. Raw remote responses are intentionally not exposed. */
public final class AgentInvocationException extends RuntimeException {
    private final int status;
    private final String code;

    public AgentInvocationException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
