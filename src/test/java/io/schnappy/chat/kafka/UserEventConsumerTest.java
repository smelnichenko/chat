package io.schnappy.chat.kafka;

import io.schnappy.chat.entity.Channel;
import io.schnappy.chat.service.SystemChannelService;
import io.schnappy.chat.service.UserCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserEventConsumerTest {

    private static final UUID USER_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Mock
    private UserCacheService userCacheService;

    @Mock
    private SystemChannelService systemChannelService;

    @InjectMocks
    private UserEventConsumer userEventConsumer;

    // --- null type ---

    @Test
    void handleUserEvent_nullType_doesNothing() {
        userEventConsumer.handleUserEvent(Map.of());
        verify(userCacheService, never()).cacheUser(any(UUID.class), anyString(), any(Boolean.class));
    }

    @Test
    void handleUserEvent_unknownType_ignoresQuietly() {
        userEventConsumer.handleUserEvent(Map.of("type", "SOMETHING_UNKNOWN"));
        verify(userCacheService, never()).cacheUser(any(UUID.class), anyString(), any(Boolean.class));
    }

    // --- USER_REGISTERED ---

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

        verify(userCacheService, never()).cacheUser(any(UUID.class), anyString(), any(Boolean.class));
    }

    @Test
    void handleUserEvent_userRegistered_missingUuid_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "USER_REGISTERED");
        event.put("email", "alice@example.com");

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).cacheUser(any(UUID.class), anyString(), any(Boolean.class));
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

        verify(userCacheService, never()).cacheUser(any(UUID.class), anyString(), any(Boolean.class));
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

        verify(userCacheService, never()).cacheUser(any(UUID.class), anyString(), any(Boolean.class));
    }

    // --- ADMIN_GRANTED ---

    @Test
    void handleUserEvent_adminGranted_addsAdminAndSyncsChannel() {
        userEventConsumer.handleUserEvent(Map.of("type", "ADMIN_GRANTED", "uuid", USER_UUID.toString()));

        verify(userCacheService).addAdminUser(USER_UUID);
        verify(systemChannelService).syncAdminChannelMembers();
    }

    @Test
    void handleUserEvent_adminGranted_missingUuid_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "ADMIN_GRANTED");

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).addAdminUser(any(UUID.class));
        verify(systemChannelService, never()).syncAdminChannelMembers();
    }

    // --- ADMIN_REVOKED ---

    @Test
    void handleUserEvent_adminRevoked_removesAdmin() {
        userEventConsumer.handleUserEvent(Map.of("type", "ADMIN_REVOKED", "uuid", USER_UUID.toString()));

        verify(userCacheService).removeAdminUser(USER_UUID);
    }

    @Test
    void handleUserEvent_adminRevoked_missingUuid_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "ADMIN_REVOKED");

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).removeAdminUser(any(UUID.class));
    }

    // --- EMAIL_VERIFIED ---

    @Test
    void handleUserEvent_emailVerified_postsSystemMessage() {
        var adminChannel = new Channel();
        adminChannel.setId(5L);
        when(systemChannelService.getOrCreateAdminChannel()).thenReturn(adminChannel);

        userEventConsumer.handleUserEvent(Map.of("type", "EMAIL_VERIFIED", "uuid", USER_UUID.toString(), "email", "alice@example.com"));

        verify(systemChannelService).getOrCreateAdminChannel();
        verify(systemChannelService).postSystemMessage(eq(5L), contains("alice@example.com"), isNull());
    }

    @Test
    void handleUserEvent_emailVerified_noEmail_usesUuid() {
        var adminChannel = new Channel();
        adminChannel.setId(5L);
        when(systemChannelService.getOrCreateAdminChannel()).thenReturn(adminChannel);

        Map<String, Object> event = new HashMap<>();
        event.put("type", "EMAIL_VERIFIED");
        event.put("uuid", USER_UUID.toString());

        userEventConsumer.handleUserEvent(event);

        verify(systemChannelService).postSystemMessage(eq(5L), contains(USER_UUID.toString()), isNull());
    }

    @Test
    void handleUserEvent_emailVerified_systemChannelThrows_doesNotPropagate() {
        when(systemChannelService.getOrCreateAdminChannel()).thenThrow(new RuntimeException("no admins"));

        assertThatCode(() ->
                userEventConsumer.handleUserEvent(Map.of("type", "EMAIL_VERIFIED", "uuid", USER_UUID.toString(), "email", "alice@example.com"))
        ).doesNotThrowAnyException();
    }

    @Test
    void handleUserEvent_emailVerified_missingUuid_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "EMAIL_VERIFIED");

        userEventConsumer.handleUserEvent(event);

        verify(systemChannelService, never()).getOrCreateAdminChannel();
    }

    // --- REGISTRATION_APPROVED ---

    @Test
    void handleUserEvent_registrationApproved_cachesUserAndPostsMessage() {
        var adminChannel = new Channel();
        adminChannel.setId(5L);
        when(systemChannelService.getOrCreateAdminChannel()).thenReturn(adminChannel);

        userEventConsumer.handleUserEvent(Map.of("type", "REGISTRATION_APPROVED", "uuid", USER_UUID.toString(), "email", "alice@example.com"));

        verify(userCacheService).cacheUser(USER_UUID, "alice@example.com", true);
        verify(systemChannelService).postSystemMessage(eq(5L), contains("alice@example.com"), isNull());
    }

    @Test
    void handleUserEvent_registrationApproved_noEmail_usesUnknown() {
        var adminChannel = new Channel();
        adminChannel.setId(5L);
        when(systemChannelService.getOrCreateAdminChannel()).thenReturn(adminChannel);

        Map<String, Object> event = new HashMap<>();
        event.put("type", "REGISTRATION_APPROVED");
        event.put("uuid", USER_UUID.toString());

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService).cacheUser(USER_UUID, "unknown", true);
        verify(systemChannelService).postSystemMessage(eq(5L), contains(USER_UUID.toString()), isNull());
    }

    @Test
    void handleUserEvent_registrationApproved_systemChannelThrows_doesNotPropagate() {
        when(systemChannelService.getOrCreateAdminChannel()).thenThrow(new RuntimeException("db down"));

        assertThatCode(() ->
                userEventConsumer.handleUserEvent(Map.of("type", "REGISTRATION_APPROVED", "uuid", USER_UUID.toString(), "email", "alice@example.com"))
        ).doesNotThrowAnyException();
    }

    // --- REGISTRATION_DECLINED ---

    @Test
    void handleUserEvent_registrationDeclined_postsSystemMessage() {
        var adminChannel = new Channel();
        adminChannel.setId(5L);
        when(systemChannelService.getOrCreateAdminChannel()).thenReturn(adminChannel);

        userEventConsumer.handleUserEvent(Map.of("type", "REGISTRATION_DECLINED", "uuid", USER_UUID.toString(), "email", "bob@example.com"));

        verify(systemChannelService).postSystemMessage(eq(5L), contains("bob@example.com"), isNull());
    }

    @Test
    void handleUserEvent_registrationDeclined_systemChannelThrows_doesNotPropagate() {
        when(systemChannelService.getOrCreateAdminChannel()).thenThrow(new RuntimeException("db down"));

        assertThatCode(() ->
                userEventConsumer.handleUserEvent(Map.of("type", "REGISTRATION_DECLINED", "uuid", USER_UUID.toString(), "email", "bob@example.com"))
        ).doesNotThrowAnyException();
    }

    // --- invalid uuid ---

    @Test
    void handleUserEvent_invalidUuidString_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "USER_REGISTERED");
        event.put("uuid", "not-a-uuid");
        event.put("email", "alice@example.com");

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).cacheUser(any(UUID.class), anyString(), any(Boolean.class));
    }

    private static Long eq(Long value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
