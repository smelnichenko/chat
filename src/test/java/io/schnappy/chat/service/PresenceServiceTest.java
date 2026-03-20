package io.schnappy.chat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOps;

    @InjectMocks
    private PresenceService presenceService;

    @Test
    void setOnline_addsUserToSortedSet() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        presenceService.setOnline(42L);

        verify(zSetOps).add(eq("chat:presence"), eq("42"), anyDouble());
    }

    @Test
    void setOffline_removesUserFromSortedSet() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        presenceService.setOffline(42L);

        verify(zSetOps).remove("chat:presence", "42");
    }

    @Test
    void getOnlineUsers_returnsUserIdsFromRedis() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.rangeByScore(eq("chat:presence"), anyDouble(), eq(Double.MAX_VALUE)))
                .thenReturn(Set.of("1", "2", "3"));

        Set<Long> online = presenceService.getOnlineUsers();

        assertThat(online).containsExactlyInAnyOrder(1L, 2L, 3L);
        // Verify stale entries are cleaned up
        verify(zSetOps).removeRangeByScore(eq("chat:presence"), eq(0.0), anyDouble());
    }

    @Test
    void getOnlineUsers_nullResult_returnsEmptySet() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.rangeByScore(eq("chat:presence"), anyDouble(), eq(Double.MAX_VALUE)))
                .thenReturn(null);

        Set<Long> online = presenceService.getOnlineUsers();

        assertThat(online).isEmpty();
    }

    @Test
    void isOnline_recentHeartbeat_returnsTrue() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        // Score is current time (within 60s TTL)
        when(zSetOps.score("chat:presence", "42")).thenReturn((double) System.currentTimeMillis());

        boolean online = presenceService.isOnline(42L);

        assertThat(online).isTrue();
    }

    @Test
    void isOnline_staleHeartbeat_returnsFalse() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        // Score is 2 minutes ago (beyond 60s TTL)
        when(zSetOps.score("chat:presence", "42"))
                .thenReturn((double) (System.currentTimeMillis() - 120_000));

        boolean online = presenceService.isOnline(42L);

        assertThat(online).isFalse();
    }

    @Test
    void isOnline_nullScore_returnsFalse() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.score("chat:presence", "42")).thenReturn(null);

        boolean online = presenceService.isOnline(42L);

        assertThat(online).isFalse();
    }
}
