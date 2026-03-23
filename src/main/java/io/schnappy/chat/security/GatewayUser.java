package io.schnappy.chat.security;

import java.util.List;

/**
 * User identity extracted from gateway X-User-* headers.
 * Primary identifier is uuid (from Keycloak JWT subject).
 * The userId (Long) is resolved lazily from the local user table.
 */
public record GatewayUser(
        String uuid,
        String email,
        List<String> permissions,
        Long userId
) {
    public static final String REQUEST_ATTRIBUTE = "gatewayUser";

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
