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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserCacheServiceTest {

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
        when(chatUserRepository.findById(10L)).thenReturn(Optional.empty());
        when(chatUserRepository.save(org.mockito.ArgumentMatchers.any(ChatUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        userCacheService.cacheUser(10L, "alice@example.com", true);

        var captor = ArgumentCaptor.forClass(ChatUser.class);
        verify(chatUserRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(captor.getValue().isEnabled()).isTrue();

        verify(valueOps).set("chat:user:email:10", "alice@example.com");
        verify(valueOps).set("chat:user:enabled:10", "true");
    }

    @Test
    void cacheUser_existingUser_updatesEmailAndEnabled() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        var existing = new ChatUser();
        existing.setId(10L);
        existing.setEmail("old@example.com");
        existing.setEnabled(true);
        when(chatUserRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(chatUserRepository.save(org.mockito.ArgumentMatchers.any(ChatUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        userCacheService.cacheUser(10L, "new@example.com", false);

        assertThat(existing.getEmail()).isEqualTo("new@example.com");
        assertThat(existing.isEnabled()).isFalse();
        verify(valueOps).set("chat:user:enabled:10", "false");
    }

    // --- getEmail ---

    @Test
    void getEmail_redisHit_returnsFromRedisWithoutPgQuery() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:email:10")).thenReturn("alice@example.com");

        String email = userCacheService.getEmail(10L);

        assertThat(email).isEqualTo("alice@example.com");
        verify(chatUserRepository, never()).findById(10L);
    }

    @Test
    void getEmail_redisMiss_fallsBackToPostgres() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:email:10")).thenReturn(null);

        var user = new ChatUser();
        user.setId(10L);
        user.setEmail("alice@example.com");
        user.setEnabled(true);
        when(chatUserRepository.findById(10L)).thenReturn(Optional.of(user));

        String email = userCacheService.getEmail(10L);

        assertThat(email).isEqualTo("alice@example.com");
        verify(valueOps).set("chat:user:email:10", "alice@example.com");
        verify(valueOps).set("chat:user:enabled:10", "true");
    }

    @Test
    void getEmail_redisMissAndPgMiss_returnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:email:10")).thenReturn(null);
        when(chatUserRepository.findById(10L)).thenReturn(Optional.empty());

        String email = userCacheService.getEmail(10L);

        assertThat(email).isNull();
    }

    // --- isEnabled ---

    @Test
    void isEnabled_redisHit_returnsFromRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:enabled:10")).thenReturn("false");

        boolean enabled = userCacheService.isEnabled(10L);

        assertThat(enabled).isFalse();
        verify(chatUserRepository, never()).findById(10L);
    }

    @Test
    void isEnabled_redisMiss_fallsBackToPostgres() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:enabled:10")).thenReturn(null);

        var user = new ChatUser();
        user.setId(10L);
        user.setEnabled(false);
        user.setEmail("alice@example.com");
        when(chatUserRepository.findById(10L)).thenReturn(Optional.of(user));

        boolean enabled = userCacheService.isEnabled(10L);

        assertThat(enabled).isFalse();
        verify(valueOps).set("chat:user:enabled:10", "false");
    }

    @Test
    void isEnabled_redisMissAndPgMiss_defaultsTrue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:enabled:10")).thenReturn(null);
        when(chatUserRepository.findById(10L)).thenReturn(Optional.empty());

        boolean enabled = userCacheService.isEnabled(10L);

        assertThat(enabled).isTrue();
    }

    // --- setAdmin ---

    @Test
    void setAdmin_true_addsToRedisSet() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        var user = new ChatUser();
        user.setId(10L);
        when(chatUserRepository.findById(10L)).thenReturn(Optional.of(user));
        when(chatUserRepository.save(org.mockito.ArgumentMatchers.any(ChatUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        userCacheService.setAdmin(10L, true);

        assertThat(user.isAdmin()).isTrue();
        verify(setOps).add("chat:admin-users", "10");
    }

    @Test
    void setAdmin_false_removesFromRedisSet() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        var user = new ChatUser();
        user.setId(10L);
        user.setAdmin(true);
        when(chatUserRepository.findById(10L)).thenReturn(Optional.of(user));
        when(chatUserRepository.save(org.mockito.ArgumentMatchers.any(ChatUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        userCacheService.setAdmin(10L, false);

        assertThat(user.isAdmin()).isFalse();
        verify(setOps).remove("chat:admin-users", "10");
    }

    @Test
    void setAdmin_userNotFound_stillUpdatesRedis() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(chatUserRepository.findById(10L)).thenReturn(Optional.empty());

        userCacheService.setAdmin(10L, true);

        verify(setOps).add("chat:admin-users", "10");
        verify(chatUserRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // --- getAdminUserIds ---

    @Test
    void getAdminUserIds_redisHit_returnsFromRedisWithoutPgQuery() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members("chat:admin-users")).thenReturn(Set.of("1", "2"));

        var admins = userCacheService.getAdminUserIds();

        assertThat(admins).containsExactlyInAnyOrder(1L, 2L);
        verify(chatUserRepository, never()).findByAdminTrue();
    }

    @Test
    void getAdminUserIds_redisMiss_fallsBackToPostgres() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members("chat:admin-users")).thenReturn(null);

        var adminUser = new ChatUser();
        adminUser.setId(5L);
        when(chatUserRepository.findByAdminTrue()).thenReturn(List.of(adminUser));

        var admins = userCacheService.getAdminUserIds();

        assertThat(admins).containsExactly(5L);
        verify(setOps).add("chat:admin-users", "5");
    }

    @Test
    void getAdminUserIds_emptyRedisSet_fallsBackToPostgres() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members("chat:admin-users")).thenReturn(Set.of());
        when(chatUserRepository.findByAdminTrue()).thenReturn(List.of());

        var admins = userCacheService.getAdminUserIds();

        assertThat(admins).isEmpty();
    }

    // --- getUserInfo ---

    @Test
    void getUserInfo_returnsEmailFromCache() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:email:10")).thenReturn("alice@example.com");

        var info = userCacheService.getUserInfo(10L, null);

        assertThat(info).containsEntry("id", 10L).containsEntry("email", "alice@example.com");
    }

    @Test
    void getUserInfo_noEmail_usesFallback() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:email:10")).thenReturn(null);
        when(chatUserRepository.findById(10L)).thenReturn(Optional.empty());

        var info = userCacheService.getUserInfo(10L, "fallback@example.com");

        assertThat(info).containsEntry("email", "fallback@example.com");
    }

    @Test
    void getUserInfo_noEmailAndNoFallback_returnsUnknown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("chat:user:email:10")).thenReturn(null);
        when(chatUserRepository.findById(10L)).thenReturn(Optional.empty());

        var info = userCacheService.getUserInfo(10L, null);

        assertThat(info).containsEntry("email", "unknown");
    }
}
