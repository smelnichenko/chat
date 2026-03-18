package io.schnappy.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Local read model for user data. Populated from user.events Kafka topic.
 * Falls back to JWT email if cache miss (profile updates are rare).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCacheService {

    private static final String USER_EMAIL_KEY = "chat:user:email:";
    private static final String USER_ENABLED_KEY = "chat:user:enabled:";
    private static final String ADMIN_USERS_KEY = "chat:admin-users";

    private final StringRedisTemplate redisTemplate;

    public void cacheUser(Long userId, String email, boolean enabled) {
        redisTemplate.opsForValue().set(USER_EMAIL_KEY + userId, email);
        redisTemplate.opsForValue().set(USER_ENABLED_KEY + userId, String.valueOf(enabled));
    }

    public String getEmail(Long userId) {
        return redisTemplate.opsForValue().get(USER_EMAIL_KEY + userId);
    }

    public boolean isEnabled(Long userId) {
        String val = redisTemplate.opsForValue().get(USER_ENABLED_KEY + userId);
        return val == null || Boolean.parseBoolean(val);
    }

    public void addAdminUser(Long userId) {
        redisTemplate.opsForSet().add(ADMIN_USERS_KEY, userId.toString());
    }

    public void removeAdminUser(Long userId) {
        redisTemplate.opsForSet().remove(ADMIN_USERS_KEY, userId.toString());
    }

    public Set<Long> getAdminUserIds() {
        var members = redisTemplate.opsForSet().members(ADMIN_USERS_KEY);
        if (members == null) return Set.of();
        return members.stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    /**
     * Returns user info map for display. Uses cached email, falls back to provided JWT email.
     */
    public Map<String, Object> getUserInfo(Long userId, String jwtEmail) {
        String email = getEmail(userId);
        if (email == null) email = jwtEmail;
        return Map.of("id", userId, "email", email != null ? email : "unknown");
    }
}
