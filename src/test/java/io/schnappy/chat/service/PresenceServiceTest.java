package io.schnappy.chat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    private static final UUID USER_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOps;

    @InjectMocks
    private PresenceService presenceService;

    @Test
    void setOnline_addsUserToSortedSet() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        presenceService.setOnline(USER_UUID);

        verify(zSetOps).add(eq("chat:presence"), eq(USER_UUID.toString()), anyDouble());
    }

    @Test
    void setOffline_removesUserFromSortedSet() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        presenceService.setOffline(USER_UUID);

        verify(zSetOps).remove("chat:presence", USER_UUID.toString());
    }

    @Test
    void getOnlineUsers_returnsUserUuidsFromRedis() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        when(zSetOps.rangeByScore(eq("chat:presence"), anyDouble(), eq(Double.MAX_VALUE)))
                .thenReturn(Set.of(uuid1.toString(), uuid2.toString()));

        Set<UUID> online = presenceService.getOnlineUsers();

        assertThat(online).containsExactlyInAnyOrder(uuid1, uuid2);
        // Verify stale entries are cleaned up
        verify(zSetOps).removeRangeByScore(eq("chat:presence"), eq(0.0), anyDouble());
    }

    @Test
    void getOnlineUsers_nullResult_returnsEmptySet() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.rangeByScore(eq("chat:presence"), anyDouble(), eq(Double.MAX_VALUE)))
                .thenReturn(null);

        Set<UUID> online = presenceService.getOnlineUsers();

        assertThat(online).isEmpty();
    }

    @Test
    void isOnline_recentHeartbeat_returnsTrue() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        // Score is current time (within 60s TTL)
        when(zSetOps.score("chat:presence", USER_UUID.toString())).thenReturn((double) System.currentTimeMillis());

        boolean online = presenceService.isOnline(USER_UUID);

        assertThat(online).isTrue();
    }

    @Test
    void isOnline_staleHeartbeat_returnsFalse() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        // Score is 2 minutes ago (beyond 60s TTL)
        when(zSetOps.score("chat:presence", USER_UUID.toString()))
                .thenReturn((double) (System.currentTimeMillis() - 120_000));

        boolean online = presenceService.isOnline(USER_UUID);

        assertThat(online).isFalse();
    }

    @Test
    void isOnline_nullScore_returnsFalse() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.score("chat:presence", USER_UUID.toString())).thenReturn(null);

        boolean online = presenceService.isOnline(USER_UUID);

        assertThat(online).isFalse();
    }
}
