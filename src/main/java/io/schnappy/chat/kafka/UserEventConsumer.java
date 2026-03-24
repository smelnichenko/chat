package io.schnappy.chat.kafka;

import io.schnappy.chat.service.SystemChannelService;
import io.schnappy.chat.service.UserCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Consumes user.events from the admin/user service to maintain
 * the local user cache in Redis and handle admin notifications.
 * Uses UUID as the primary user identifier.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventConsumer {

    private static final String EVENT_UUID = "uuid";
    private static final String EVENT_EMAIL = "email";
    private static final String USER_PREFIX = "user ";

    private final UserCacheService userCacheService;
    private final SystemChannelService systemChannelService;

    @KafkaListener(topics = "user.events", groupId = "chat-user")
    public void handleUserEvent(Map<String, Object> event) {
        String type = (String) event.get("type");
        if (type == null) {
            log.warn("Received user event without type: {}", event);
            return;
        }

        switch (type) {
            case "USER_REGISTERED" -> handleUserRegistered(event);
            case "USER_ENABLED", "USER_DISABLED" -> handleUserEnabledChanged(event);
            case "PROFILE_UPDATED" -> handleProfileUpdated(event);
            case "ADMIN_GRANTED" -> handleAdminGranted(event);
            case "ADMIN_REVOKED" -> handleAdminRevoked(event);
            case "EMAIL_VERIFIED" -> handleEmailVerified(event);
            case "REGISTRATION_APPROVED" -> handleRegistrationApproved(event);
            case "REGISTRATION_DECLINED" -> handleRegistrationDeclined(event);
            default -> log.debug("Ignoring user event type: {}", type);
        }
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

    private void handleAdminGranted(Map<String, Object> event) {
        UUID uuid = toUuid(event.get(EVENT_UUID));
        if (uuid == null) return;

        userCacheService.addAdminUser(uuid);
        systemChannelService.syncAdminChannelMembers();
        log.info("Admin granted to user {}", uuid);
    }

    private void handleAdminRevoked(Map<String, Object> event) {
        UUID uuid = toUuid(event.get(EVENT_UUID));
        if (uuid == null) return;

        userCacheService.removeAdminUser(uuid);
        log.info("Admin revoked from user {}", uuid);
    }

    private void handleEmailVerified(Map<String, Object> event) {
        UUID uuid = toUuid(event.get(EVENT_UUID));
        String email = (String) event.get(EVENT_EMAIL);
        if (uuid == null) return;

        try {
            var channel = systemChannelService.getOrCreateAdminChannel();
            systemChannelService.postSystemMessage(channel.getId(),
                "New user verified: " + (email != null ? email : USER_PREFIX + uuid),
                null);
        } catch (RuntimeException e) {
            log.warn("Failed to post email verification notification: {}", e.getMessage());
        }
    }

    private void handleRegistrationApproved(Map<String, Object> event) {
        UUID uuid = toUuid(event.get(EVENT_UUID));
        String email = (String) event.get(EVENT_EMAIL);
        if (uuid == null) return;

        userCacheService.cacheUser(uuid, email != null ? email : "unknown", true);

        try {
            var channel = systemChannelService.getOrCreateAdminChannel();
            systemChannelService.postSystemMessage(channel.getId(),
                "Registration approved: " + (email != null ? email : USER_PREFIX + uuid),
                null);
        } catch (RuntimeException e) {
            log.warn("Failed to post approval notification: {}", e.getMessage());
        }
    }

    private void handleRegistrationDeclined(Map<String, Object> event) {
        UUID uuid = toUuid(event.get(EVENT_UUID));
        String email = (String) event.get(EVENT_EMAIL);
        if (uuid == null) return;

        try {
            var channel = systemChannelService.getOrCreateAdminChannel();
            systemChannelService.postSystemMessage(channel.getId(),
                "Registration declined: " + (email != null ? email : USER_PREFIX + uuid),
                null);
        } catch (RuntimeException e) {
            log.warn("Failed to post decline notification: {}", e.getMessage());
        }
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
