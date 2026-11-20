package io.schnappy.chat.kafka;

import io.schnappy.chat.service.UserCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Consumes user.events from the admin service to maintain chat's user store (chat_users, its Redis cache). Uses UUID
 * as the primary user identifier.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventConsumer {

    private static final String EVENT_UUID = "uuid";
    private static final String EVENT_EMAIL = "email";

    private final UserCacheService userCacheService;

    @KafkaListener(topics = "user.events", groupId = "chat-user")
    public void handleUserEvent(Map<String, Object> event) {
        String type = (String) event.get("type");
        if (type == null) {
            log.warn("Received user event without type: {}", event);
            return;
        }

        switch (type) {
            case "USER_CREATED" -> handleUserCreated(event);
            case "USER_REGISTERED" -> handleUserRegistered(event);
            case "USER_ENABLED", "USER_DISABLED" -> handleUserEnabledChanged(event);
            case "PROFILE_UPDATED" -> handleProfileUpdated(event);
            default -> log.debug("Ignoring user event type: {}", type);
        }
    }

    /**
     * A user admin knows - provisioned at its first sign-in, or synced again: chat's user store (chat_users, its cache)
     * takes it with admin's enabled. The one creation event admin sends; without it a new user never reached chat - not
     * invitable ("User not found"), and its every request asked admin to provision it again.
     */
    private void handleUserCreated(Map<String, Object> event) {
        UUID uuid = toUuid(event.get(EVENT_UUID));
        String email = (String) event.get(EVENT_EMAIL);
        if (uuid == null || email == null) return;

        boolean enabled = !Boolean.FALSE.equals(event.get("enabled"));
        userCacheService.cacheUser(uuid, email, enabled);
        log.info("Cached user from admin: {} ({}) enabled={}", uuid, email, enabled);
    }

    private void handleUserRegistered(Map<String, Object> event) {
        UUID uuid = toUuid(event.get(EVENT_UUID));
        String email = (String) event.get(EVENT_EMAIL);
        if (uuid == null || email == null) return;

        userCacheService.cacheUser(uuid, email, true);
        log.info("Cached new user: {} ({})", uuid, email);
    }

    private void handleUserEnabledChanged(Map<String, Object> event) {
        UUID uuid = toUuid(event.get(EVENT_UUID));
        String email = (String) event.get(EVENT_EMAIL);
        boolean enabled = "USER_ENABLED".equals(event.get("type"));
        if (uuid == null) return;

        userCacheService.cacheUser(uuid, email != null ? email : "unknown", enabled);
        log.info("User {} {}", uuid, enabled ? "enabled" : "disabled");
    }

    private void handleProfileUpdated(Map<String, Object> event) {
        UUID uuid = toUuid(event.get(EVENT_UUID));
        String email = (String) event.get(EVENT_EMAIL);
        if (uuid == null || email == null) return;

        userCacheService.cacheUser(uuid, email, true);
    }

    private UUID toUuid(Object value) {
        if (value instanceof String s && !s.isBlank()) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException _) {
                return null;
            }
        }
        return null;
    }
}
