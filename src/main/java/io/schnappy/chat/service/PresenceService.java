package io.schnappy.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PresenceService {

    private static final String PRESENCE_KEY = "chat:presence";
    private static final Duration HEARTBEAT_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;

    public void setOnline(Long userId) {
        redisTemplate.opsForZSet().add(PRESENCE_KEY, userId.toString(),
            System.currentTimeMillis());
    }

    public void setOffline(Long userId) {
        redisTemplate.opsForZSet().remove(PRESENCE_KEY, userId.toString());
    }

    public Set<Long> getOnlineUsers() {
        long cutoff = System.currentTimeMillis() - HEARTBEAT_TTL.toMillis();
        redisTemplate.opsForZSet().removeRangeByScore(PRESENCE_KEY, 0, cutoff);
        var members = redisTemplate.opsForZSet().rangeByScore(PRESENCE_KEY, cutoff, Double.MAX_VALUE);
        if (members == null) return Set.of();
        return members.stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    public boolean isOnline(Long userId) {
        Double score = redisTemplate.opsForZSet().score(PRESENCE_KEY, userId.toString());
        if (score == null) return false;
        return score > System.currentTimeMillis() - HEARTBEAT_TTL.toMillis();
    }
}
