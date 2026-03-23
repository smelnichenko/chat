package io.schnappy.chat.security;

/**
 * Resolves a user's internal Long ID from their UUID string.
 * Implemented outside the security package to avoid coupling
 * security to the repository layer.
 */
@FunctionalInterface
public interface UserIdResolver {

    /**
     * @param uuid the user's UUID (from Keycloak JWT subject)
     * @return the internal Long user ID, or null if not found
     */
    Long resolve(String uuid);
}
