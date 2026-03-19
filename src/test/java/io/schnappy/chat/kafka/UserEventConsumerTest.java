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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserEventConsumerTest {

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
        verify(userCacheService, never()).cacheUser(anyLong(), anyString(), any(Boolean.class));
    }

    @Test
    void handleUserEvent_unknownType_ignoresQuietly() {
        userEventConsumer.handleUserEvent(Map.of("type", "SOMETHING_UNKNOWN"));
        verify(userCacheService, never()).cacheUser(anyLong(), anyString(), any(Boolean.class));
    }

    // --- USER_REGISTERED ---

    @Test
    void handleUserEvent_userRegistered_cachesUser() {
        userEventConsumer.handleUserEvent(Map.of("type", "USER_REGISTERED", "userId", 42L, "email", "alice@example.com"));

        verify(userCacheService).cacheUser(42L, "alice@example.com", true);
    }

    @Test
    void handleUserEvent_userRegistered_missingEmail_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "USER_REGISTERED");
        event.put("userId", 42L);
        // no email

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).cacheUser(anyLong(), anyString(), any(Boolean.class));
    }

    @Test
    void handleUserEvent_userRegistered_missingUserId_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "USER_REGISTERED");
        event.put("email", "alice@example.com");

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).cacheUser(anyLong(), anyString(), any(Boolean.class));
    }

    // --- USER_ENABLED / USER_DISABLED ---

    @Test
    void handleUserEvent_userEnabled_cachesUserAsEnabled() {
        userEventConsumer.handleUserEvent(Map.of("type", "USER_ENABLED", "userId", 42L, "email", "alice@example.com"));

        verify(userCacheService).cacheUser(42L, "alice@example.com", true);
    }

    @Test
    void handleUserEvent_userDisabled_cachesUserAsDisabled() {
        userEventConsumer.handleUserEvent(Map.of("type", "USER_DISABLED", "userId", 42L, "email", "alice@example.com"));

        verify(userCacheService).cacheUser(42L, "alice@example.com", false);
    }

    @Test
    void handleUserEvent_userEnabledWithoutEmail_usesUnknown() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "USER_ENABLED");
        event.put("userId", 42L);

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService).cacheUser(42L, "unknown", true);
    }

    @Test
    void handleUserEvent_userEnabled_missingUserId_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "USER_ENABLED");

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).cacheUser(anyLong(), anyString(), any(Boolean.class));
    }

    // --- PROFILE_UPDATED ---

    @Test
    void handleUserEvent_profileUpdated_cachesUser() {
        userEventConsumer.handleUserEvent(Map.of("type", "PROFILE_UPDATED", "userId", 42L, "email", "new@example.com"));

        verify(userCacheService).cacheUser(42L, "new@example.com", true);
    }

    @Test
    void handleUserEvent_profileUpdated_missingEmail_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "PROFILE_UPDATED");
        event.put("userId", 42L);

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).cacheUser(anyLong(), anyString(), any(Boolean.class));
    }

    // --- ADMIN_GRANTED ---

    @Test
    void handleUserEvent_adminGranted_addsAdminAndSyncsChannel() {
        userEventConsumer.handleUserEvent(Map.of("type", "ADMIN_GRANTED", "userId", 42L));

        verify(userCacheService).addAdminUser(42L);
        verify(systemChannelService).syncAdminChannelMembers();
    }

    @Test
    void handleUserEvent_adminGranted_missingUserId_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "ADMIN_GRANTED");

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).addAdminUser(anyLong());
        verify(systemChannelService, never()).syncAdminChannelMembers();
    }

    // --- ADMIN_REVOKED ---

    @Test
    void handleUserEvent_adminRevoked_removesAdmin() {
        userEventConsumer.handleUserEvent(Map.of("type", "ADMIN_REVOKED", "userId", 42L));

        verify(userCacheService).removeAdminUser(42L);
    }

    @Test
    void handleUserEvent_adminRevoked_missingUserId_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "ADMIN_REVOKED");

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).removeAdminUser(anyLong());
    }

    // --- EMAIL_VERIFIED ---

    @Test
    void handleUserEvent_emailVerified_postsSystemMessage() throws Exception {
        var adminChannel = new Channel();
        adminChannel.setId(5L);
        when(systemChannelService.getOrCreateAdminChannel()).thenReturn(adminChannel);

        userEventConsumer.handleUserEvent(Map.of("type", "EMAIL_VERIFIED", "userId", 42L, "email", "alice@example.com"));

        verify(systemChannelService).getOrCreateAdminChannel();
        verify(systemChannelService).postSystemMessage(eq(5L), contains("alice@example.com"), isNull());
    }

    @Test
    void handleUserEvent_emailVerified_noEmail_usesUserId() throws Exception {
        var adminChannel = new Channel();
        adminChannel.setId(5L);
        when(systemChannelService.getOrCreateAdminChannel()).thenReturn(adminChannel);

        Map<String, Object> event = new HashMap<>();
        event.put("type", "EMAIL_VERIFIED");
        event.put("userId", 42L);

        userEventConsumer.handleUserEvent(event);

        verify(systemChannelService).postSystemMessage(eq(5L), contains("user #42"), isNull());
    }

    @Test
    void handleUserEvent_emailVerified_systemChannelThrows_doesNotPropagate() {
        when(systemChannelService.getOrCreateAdminChannel()).thenThrow(new RuntimeException("no admins"));

        // Should not throw
        userEventConsumer.handleUserEvent(Map.of("type", "EMAIL_VERIFIED", "userId", 42L, "email", "alice@example.com"));
    }

    @Test
    void handleUserEvent_emailVerified_missingUserId_doesNothing() {
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

        userEventConsumer.handleUserEvent(Map.of("type", "REGISTRATION_APPROVED", "userId", 42L, "email", "alice@example.com"));

        verify(userCacheService).cacheUser(42L, "alice@example.com", true);
        verify(systemChannelService).postSystemMessage(eq(5L), contains("alice@example.com"), isNull());
    }

    @Test
    void handleUserEvent_registrationApproved_noEmail_usesUnknown() {
        var adminChannel = new Channel();
        adminChannel.setId(5L);
        when(systemChannelService.getOrCreateAdminChannel()).thenReturn(adminChannel);

        Map<String, Object> event = new HashMap<>();
        event.put("type", "REGISTRATION_APPROVED");
        event.put("userId", 42L);

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService).cacheUser(42L, "unknown", true);
        verify(systemChannelService).postSystemMessage(eq(5L), contains("user #42"), isNull());
    }

    @Test
    void handleUserEvent_registrationApproved_systemChannelThrows_doesNotPropagate() {
        when(systemChannelService.getOrCreateAdminChannel()).thenThrow(new RuntimeException("db down"));

        // Should not throw
        userEventConsumer.handleUserEvent(Map.of("type", "REGISTRATION_APPROVED", "userId", 42L, "email", "alice@example.com"));
    }

    // --- REGISTRATION_DECLINED ---

    @Test
    void handleUserEvent_registrationDeclined_postsSystemMessage() {
        var adminChannel = new Channel();
        adminChannel.setId(5L);
        when(systemChannelService.getOrCreateAdminChannel()).thenReturn(adminChannel);

        userEventConsumer.handleUserEvent(Map.of("type", "REGISTRATION_DECLINED", "userId", 42L, "email", "bob@example.com"));

        verify(systemChannelService).postSystemMessage(eq(5L), contains("bob@example.com"), isNull());
    }

    @Test
    void handleUserEvent_registrationDeclined_systemChannelThrows_doesNotPropagate() {
        when(systemChannelService.getOrCreateAdminChannel()).thenThrow(new RuntimeException("db down"));

        // Should not throw
        userEventConsumer.handleUserEvent(Map.of("type", "REGISTRATION_DECLINED", "userId", 42L, "email", "bob@example.com"));
    }

    // --- toLong type coercion ---

    @Test
    void handleUserEvent_userIdAsInteger_coercedToLong() {
        // Kafka JSON deserialization may produce Integer for small numbers
        userEventConsumer.handleUserEvent(Map.of("type", "USER_REGISTERED", "userId", 42, "email", "alice@example.com"));

        verify(userCacheService).cacheUser(42L, "alice@example.com", true);
    }

    @Test
    void handleUserEvent_userIdAsString_parsedToLong() {
        userEventConsumer.handleUserEvent(Map.of("type", "USER_REGISTERED", "userId", "42", "email", "alice@example.com"));

        verify(userCacheService).cacheUser(42L, "alice@example.com", true);
    }

    @Test
    void handleUserEvent_userIdAsInvalidString_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "USER_REGISTERED");
        event.put("userId", "not-a-number");
        event.put("email", "alice@example.com");

        userEventConsumer.handleUserEvent(event);

        verify(userCacheService, never()).cacheUser(anyLong(), anyString(), any(Boolean.class));
    }

    private static Long eq(Long value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
