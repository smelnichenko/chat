package io.schnappy.chat.service;

import io.schnappy.chat.repository.ChatUserRepository;
import io.schnappy.chat.security.UserProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

/**
 * Checks if a user exists locally; if not, calls the admin service's
 * ensure-user endpoint to trigger provisioning via Kafka events.
 *
 * <p>The call relays the caller's own validated Bearer token (taken from the
 * current request) so the admin service can re-validate it — admin no longer
 * trusts X-User-* headers. {@code email}/{@code roles} are derived by admin
 * from that same token, so they are not forwarded here.
 */
@Component
public class UserProvisionerAdapter implements UserProvisioner {

    private static final Logger log = LoggerFactory.getLogger(UserProvisionerAdapter.class);

    private final ChatUserRepository chatUserRepository;
    private final RestClient restClient;

    public UserProvisionerAdapter(ChatUserRepository chatUserRepository,
                                  @Value("${ADMIN_SERVICE_URL:http://schnappy-admin:8080}") String adminServiceUrl) {
        this.chatUserRepository = chatUserRepository;
        this.restClient = RestClient.builder()
                .baseUrl(adminServiceUrl)
                .build();
    }

    @Override
    public void provisionUser(String uuid, String email, List<String> roles) {
        try {
            if (chatUserRepository.findByUuid(UUID.fromString(uuid)).isPresent()) {
                return;
            }

            String authorization = currentBearerToken();
            if (authorization == null) {
                log.warn("No bearer token on the current request; skipping provisioning for user {}", uuid);
                return;
            }

            log.info("User {} not found locally, calling admin ensure-user", uuid);

            restClient.post()
                    .uri("/api/auth/ensure-user")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Successfully triggered provisioning for user {}", uuid);
        } catch (Exception e) {
            log.warn("Failed to provision user {}: {}", uuid, e.getMessage());
        }
    }

    /** The current request's Authorization header (the validated Bearer token), or null. */
    private static String currentBearerToken() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        }
        return null;
    }
}
