package io.schnappy.chat.kafka;

import io.schnappy.chat.service.UserCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserEventConsumerTest {

    private static final UUID USER_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Mock
    private UserCacheService userCacheService;


    @InjectMocks
    private UserEventConsumer userEventConsumer;

    // --- null type ---

    @Test
    void handleUserEvent_nullType_doesNothing() {
        userEventConsumer.handleUserEvent(Map.of());
        verify(userCacheService, never()).cacheUser(any(), any(), anyBoolean());
    }

    @Test
    void handleUserEvent_unknownType_ignoresQuietly() {
        userEventConsumer.handleUserEvent(Map.of("type", "SOMETHING_UNKNOWN"));
        verify(userCacheService, never()).cacheUser(any(), any(), anyBoolean());
    }

    // --- USER_REGISTERED ---

    @Test
    void handleUserEvent_userCreated_asAdminSendsIt_cachedWithItsEnabled() {
        // admin's one creation event (a first sign-in, a sync): chat's user store takes it
        userEventConsumer.handleUserEvent(Map.of("type", "USER_CREATED", "uuid", USER_UUID.toString(),
                "email", "new@example.com", "enabled", true));

        verify(userCacheService).cacheUser(USER_UUID, "new@example.com", true);
    }

    @Test
    void handleUserEvent_userCreated_disabledInAdmin_cachedDisabled() {
        userEventConsumer.handleUserEvent(Map.of("type", "USER_CREATED", "uuid", USER_UUID.toString(),
                "email", "off@example.com", "enabled", false));

        verify(userCacheService).cacheUser(USER_UUID, "off@example.com", false);
    }

    @Test
    void handleUserEvent_userCreated_noUuid_nothingCached() {
        userEventConsumer.handleUserEvent(Map.of("type", "USER_CREATED", "email", "x@example.com", "enabled", true));

        verify(userCacheService, never()).cacheUser(any(), any(), anyBoolean());
    }

    @Test
    void handleUserEvent_userRegistered_cachesUser() {
        userEventConsumer.handleUserEvent(Map.of("type", "USER_REGISTERED", "uuid", USER_UUID.toString(), "email", "alice@example.com"));

        verify(userCacheService).cacheUser(USER_UUID, "alice@example.com", true);
    }

    @Test
    void handleUserEvent_userRegistered_missingEmail_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "USER_REGISTERED");
        event.put("uuid", USER_UUID.toString());
        // no email

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).cacheUser(any(), any(), anyBoolean());
    }

    @Test
    void handleUserEvent_userRegistered_missingUuid_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "USER_REGISTERED");
        event.put("email", "alice@example.com");

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).cacheUser(any(), any(), anyBoolean());
    }

    // --- USER_ENABLED / USER_DISABLED ---

    @Test
    void handleUserEvent_userEnabled_cachesUserAsEnabled() {
        userEventConsumer.handleUserEvent(Map.of("type", "USER_ENABLED", "uuid", USER_UUID.toString(), "email", "alice@example.com"));

        verify(userCacheService).cacheUser(USER_UUID, "alice@example.com", true);
    }

    @Test
    void handleUserEvent_userDisabled_cachesUserAsDisabled() {
        userEventConsumer.handleUserEvent(Map.of("type", "USER_DISABLED", "uuid", USER_UUID.toString(), "email", "alice@example.com"));

        verify(userCacheService).cacheUser(USER_UUID, "alice@example.com", false);
    }

    @Test
    void handleUserEvent_userEnabledWithoutEmail_usesUnknown() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "USER_ENABLED");
        event.put("uuid", USER_UUID.toString());

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService).cacheUser(USER_UUID, "unknown", true);
    }

    @Test
    void handleUserEvent_userEnabled_missingUuid_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "USER_ENABLED");

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).cacheUser(any(), any(), anyBoolean());
    }

    // --- PROFILE_UPDATED ---

    @Test
    void handleUserEvent_profileUpdated_cachesUser() {
        userEventConsumer.handleUserEvent(Map.of("type", "PROFILE_UPDATED", "uuid", USER_UUID.toString(), "email", "new@example.com"));

        verify(userCacheService).cacheUser(USER_UUID, "new@example.com", true);
    }

    @Test
    void handleUserEvent_profileUpdated_missingEmail_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "PROFILE_UPDATED");
        event.put("uuid", USER_UUID.toString());

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).cacheUser(any(), any(), anyBoolean());
    }






    // --- invalid uuid ---

    @Test
    void handleUserEvent_invalidUuidString_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "USER_REGISTERED");
        event.put("uuid", "not-a-uuid");
        event.put("email", "alice@example.com");

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).cacheUser(any(), any(), anyBoolean());
    }

    private static Long eq(Long value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
