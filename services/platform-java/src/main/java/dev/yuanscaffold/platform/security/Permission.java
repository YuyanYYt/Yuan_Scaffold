package dev.yuanscaffold.platform.security;

import java.util.Arrays;
import java.util.List;

public enum Permission {
    USER_READ("system:user:read"),
    USER_WRITE("system:user:write"),
    ROLE_READ("system:role:read"),
    ROLE_WRITE("system:role:write"),
    MENU_READ("system:menu:read"),
    MENU_WRITE("system:menu:write"),
    AUDIT_READ("system:audit:read"),
    PROJECT_DRAFT_READ("studio:project:read"),
    PROJECT_DRAFT_WRITE("studio:project:write");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static boolean contains(String code) {
        return Arrays.stream(values()).anyMatch(value -> value.code.equals(code));
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(Permission::code).toList();
    }
}
