package io.schnappy.chat.security;

import java.util.List;

/**
 * User identity extracted from gateway X-User-* headers.
 */
public record GatewayUser(
        Long userId,
        String uuid,
        String email,
        List<String> permissions
) {
    public static final String REQUEST_ATTRIBUTE = "gatewayUser";

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
