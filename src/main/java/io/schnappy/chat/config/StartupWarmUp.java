package io.schnappy.chat.config;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.schnappy.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Does before readiness what the first requests did on the way: Boot runs every {@link ApplicationRunner} before it
 * reports the app ready, so Kubernetes routes no traffic here until this returns.
 *
 * <p>Measured on the Vagrant copy after a restart: the first {@code GET /api/chat/channels} took 1.4-1.9 s (the
 * Keycloak key fetch over TLS, the first queries) and the next ones 10-30 ms. Both steps are best effort: a Keycloak or
 * database that does not answer here is logged and the first request does the work, as before - Keycloak is no startup
 * dependency.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupWarmUp implements ApplicationRunner {

    /** No one: a user without channels, so the channel listing reads and writes nothing. */
    static final UUID NO_USER = new UUID(0, 0);

    private final JWKSource<SecurityContext> keycloakJwkSource;
    private final ChatService chatService;

    @Override
    public void run(ApplicationArguments args) {
        warmKeys();
        warmQueries();
    }

    private void warmKeys() {
        try {
            int keys = keycloakJwkSource.get(new JWKSelector(new JWKMatcher.Builder().build()), null).size();
            log.info("Keycloak signing keys fetched before readiness: {}", keys);
        } catch (KeySourceException e) {
            log.warn("Keycloak signing keys not fetched at startup ({}) - the first authenticated request fetches them",
                    e.getMessage());
        }
    }

    private void warmQueries() {
        try {
            chatService.getAllChannelsWithMembership(NO_USER);
        } catch (DataAccessException e) {
            log.warn("Channel query not warmed at startup ({}) - the first request runs it cold", e.getMessage());
        }
    }
}
