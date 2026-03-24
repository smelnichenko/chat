package io.schnappy.chat.security;

import java.util.List;
import java.util.UUID;

/**
 * User identity extracted from gateway X-User-* headers.
 * Primary identifier is uuid (from Keycloak JWT subject).
 */
public record GatewayUser(
        UUID uuid,
        String email,
        List<String> permissions
) {
    public static final String REQUEST_ATTRIBUTE = "gatewayUser";

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
