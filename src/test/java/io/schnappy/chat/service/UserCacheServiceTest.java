package io.schnappy.chat.service;

import io.schnappy.chat.entity.ChatUser;
import io.schnappy.chat.repository.ChatUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserCacheServiceTest {

    private static final UUID USER_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ChatUserRepository chatUserRepository;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SetOperations<String, String> setOps;

    @InjectMocks
    private UserCacheService userCacheService;

    // --- cacheUser ---

    @Test
    void cacheUser_newUser_createsAndSavesToPgAndRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());
        when(chatUserRepository.save(org.mockito.ArgumentMatchers.any(ChatUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        userCacheService.cacheUser(USER_UUID, "alice@example.com", true);

        var captor = ArgumentCaptor.forClass(ChatUser.class);
        verify(chatUserRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(captor.getValue().isEnabled()).isTrue();
        assertThat(captor.getValue().getUuid()).isEqualTo(USER_UUID);

        verify(valueOps).set("chat:user:email:" + USER_UUID, "alice@example.com");
        verify(valueOps).set("chat:user:enabled:" + USER_UUID, "true");
    }

    @Test
    void cacheUser_existingUser_updatesEmailAndEnabled() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        var existing = new ChatUser();
        existing.setId(10L);
        existing.setUuid(USER_UUID);
        existing.setEmail("old@example.com");
        existing.setEnabled(true);
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(existing));
        when(chatUserRepository.save(org.mockito.ArgumentMatchers.any(ChatUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        userCacheService.cacheUser(USER_UUID, "new@example.com", false);

        assertThat(existing.getEmail()).isEqualTo("new@example.com");
        assertThat(existing.isEnabled()).isFalse();
        verify(valueOps).set("chat:user:enabled:" + USER_UUID, "false");
    }

    // --- getEmail ---

    @Test
    void getEmail_redisHit_returnsFromRedisWithoutPgQuery() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:email:" + USER_UUID)).thenReturn("alice@example.com");

        String email = userCacheService.getEmail(USER_UUID);

        assertThat(email).isEqualTo("alice@example.com");
        verify(chatUserRepository, never()).findByUuid(USER_UUID);
    }

    @Test
    void getEmail_redisMiss_fallsBackToPostgres() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:email:" + USER_UUID)).thenReturn(null);

        var user = new ChatUser();
        user.setId(10L);
        user.setUuid(USER_UUID);
        user.setEmail("alice@example.com");
        user.setEnabled(true);
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(user));

        String email = userCacheService.getEmail(USER_UUID);

        assertThat(email).isEqualTo("alice@example.com");
        verify(valueOps).set("chat:user:email:" + USER_UUID, "alice@example.com");
        verify(valueOps).set("chat:user:enabled:" + USER_UUID, "true");
    }

    @Test
    void getEmail_redisMissAndPgMiss_returnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:email:" + USER_UUID)).thenReturn(null);
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());

        String email = userCacheService.getEmail(USER_UUID);

        assertThat(email).isNull();
    }

    // --- isEnabled ---

    @Test
    void isEnabled_redisHit_returnsFromRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:enabled:" + USER_UUID)).thenReturn("false");

        boolean enabled = userCacheService.isEnabled(USER_UUID);

        assertThat(enabled).isFalse();
        verify(chatUserRepository, never()).findByUuid(USER_UUID);
    }

    @Test
    void isEnabled_redisMiss_fallsBackToPostgres() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:enabled:" + USER_UUID)).thenReturn(null);

        var user = new ChatUser();
        user.setId(10L);
        user.setUuid(USER_UUID);
        user.setEnabled(false);
        user.setEmail("alice@example.com");
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(user));

        boolean enabled = userCacheService.isEnabled(USER_UUID);

        assertThat(enabled).isFalse();
        verify(valueOps).set("chat:user:enabled:" + USER_UUID, "false");
    }

    @Test
    void isEnabled_redisMissAndPgMiss_defaultsTrue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:enabled:" + USER_UUID)).thenReturn(null);
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());

        boolean enabled = userCacheService.isEnabled(USER_UUID);

        assertThat(enabled).isTrue();
    }

    // --- setAdmin ---

    @Test
    void setAdmin_true_addsToRedisSet() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        var user = new ChatUser();
        user.setId(10L);
        user.setUuid(USER_UUID);
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(user));
        when(chatUserRepository.save(org.mockito.ArgumentMatchers.any(ChatUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        userCacheService.setAdmin(USER_UUID, true);

        assertThat(user.isAdmin()).isTrue();
        verify(setOps).add("chat:admin-users", USER_UUID.toString());
    }

    @Test
    void setAdmin_false_removesFromRedisSet() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        var user = new ChatUser();
        user.setId(10L);
        user.setUuid(USER_UUID);
        user.setAdmin(true);
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(user));
        when(chatUserRepository.save(org.mockito.ArgumentMatchers.any(ChatUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        userCacheService.setAdmin(USER_UUID, false);

        assertThat(user.isAdmin()).isFalse();
        verify(setOps).remove("chat:admin-users", USER_UUID.toString());
    }

    @Test
    void setAdmin_userNotFound_stillUpdatesRedis() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());

        userCacheService.setAdmin(USER_UUID, true);

        verify(setOps).add("chat:admin-users", USER_UUID.toString());
        verify(chatUserRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // --- getAdminUserUuids ---

    @Test
    void getAdminUserUuids_redisHit_returnsFromRedisWithoutPgQuery() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        when(setOps.members("chat:admin-users")).thenReturn(Set.of(uuid1.toString(), uuid2.toString()));

        var admins = userCacheService.getAdminUserUuids();

        assertThat(admins).containsExactlyInAnyOrder(uuid1, uuid2);
        verify(chatUserRepository, never()).findByAdminTrue();
    }

    @Test
    void getAdminUserUuids_redisMiss_fallsBackToPostgres() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members("chat:admin-users")).thenReturn(null);

        var adminUser = new ChatUser();
        adminUser.setId(5L);
        adminUser.setUuid(USER_UUID);
        when(chatUserRepository.findByAdminTrue()).thenReturn(List.of(adminUser));

        var admins = userCacheService.getAdminUserUuids();

        assertThat(admins).containsExactly(USER_UUID);
        verify(setOps).add("chat:admin-users", USER_UUID.toString());
    }

    @Test
    void getAdminUserUuids_emptyRedisSet_fallsBackToPostgres() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members("chat:admin-users")).thenReturn(Set.of());
        when(chatUserRepository.findByAdminTrue()).thenReturn(List.of());

        var admins = userCacheService.getAdminUserUuids();

        assertThat(admins).isEmpty();
    }

    // --- getUserInfo ---

    @Test
    void getUserInfo_returnsEmailFromCache() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:email:" + USER_UUID)).thenReturn("alice@example.com");

        var info = userCacheService.getUserInfo(USER_UUID, null);

        assertThat(info).containsEntry("id", USER_UUID.toString()).containsEntry("email", "alice@example.com");
    }

    @Test
    void getUserInfo_noEmail_usesFallback() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:email:" + USER_UUID)).thenReturn(null);
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());

        var info = userCacheService.getUserInfo(USER_UUID, "fallback@example.com");

        assertThat(info).containsEntry("email", "fallback@example.com");
    }

    @Test
    void getUserInfo_noEmailAndNoFallback_returnsUnknown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:email:" + USER_UUID)).thenReturn(null);
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());

        var info = userCacheService.getUserInfo(USER_UUID, null);

        assertThat(info).containsEntry("email", "unknown");
    }

    // --- addAdminUser / removeAdminUser ---

    @Test
    void addAdminUser_delegatesToSetAdminTrue() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        var user = new ChatUser();
        user.setId(10L);
        user.setUuid(USER_UUID);
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(user));
        when(chatUserRepository.save(org.mockito.ArgumentMatchers.any(ChatUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        userCacheService.addAdminUser(USER_UUID);

        assertThat(user.isAdmin()).isTrue();
        verify(setOps).add("chat:admin-users", USER_UUID.toString());
    }

    @Test
    void removeAdminUser_delegatesToSetAdminFalse() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        var user = new ChatUser();
        user.setId(10L);
        user.setUuid(USER_UUID);
        user.setAdmin(true);
        when(chatUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(user));
        when(chatUserRepository.save(org.mockito.ArgumentMatchers.any(ChatUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        userCacheService.removeAdminUser(USER_UUID);

        assertThat(user.isAdmin()).isFalse();
        verify(setOps).remove("chat:admin-users", USER_UUID.toString());
    }
}
