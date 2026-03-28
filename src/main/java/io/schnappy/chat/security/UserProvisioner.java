package io.schnappy.chat.security;

import java.util.List;

/**
 * Triggers user provisioning on first authenticated request.
 * Interface lives in security/ so GatewayAuthFilter can depend on it
 * without violating architecture constraints.
 */
@FunctionalInterface
public interface UserProvisioner {

    void provisionUser(String uuid, String email, List<String> roles);
}
