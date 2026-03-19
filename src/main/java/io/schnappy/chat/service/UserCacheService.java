package io.schnappy.chat.service;

import io.schnappy.chat.entity.ChatUser;
import io.schnappy.chat.repository.ChatUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * User cache backed by PostgreSQL (chat_users table) with Redis as read-through cache.
 * Populated from Kafka user.events and gateway headers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCacheService {

    private static final String USER_EMAIL_KEY = "chat:user:email:";
    private static final String USER_ENABLED_KEY = "chat:user:enabled:";
    private static final String ADMIN_USERS_KEY = "chat:admin-users";

    private final StringRedisTemplate redisTemplate;
    private final ChatUserRepository chatUserRepository;

    public void cacheUser(Long userId, String email, boolean enabled) {
        // Write to PostgreSQL (source of truth)
        var user = chatUserRepository.findById(userId).orElseGet(() -> {
            var u = new ChatUser();
            u.setId(userId);
            return u;
        });
        user.setEmail(email);
        user.setEnabled(enabled);
        user.setUpdatedAt(Instant.now());
        chatUserRepository.save(user);

        // Write to Redis (cache)
        redisTemplate.opsForValue().set(USER_EMAIL_KEY + userId, email);
        redisTemplate.opsForValue().set(USER_ENABLED_KEY + userId, String.valueOf(enabled));
    }

    public String getEmail(Long userId) {
        // Try Redis first
        String email = redisTemplate.opsForValue().get(USER_EMAIL_KEY + userId);
        if (email != null) return email;

        // Fall back to PostgreSQL
        return chatUserRepository.findById(userId).map(user -> {
            // Populate Redis cache
            redisTemplate.opsForValue().set(USER_EMAIL_KEY + userId, user.getEmail());
            redisTemplate.opsForValue().set(USER_ENABLED_KEY + userId, String.valueOf(user.isEnabled()));
            return user.getEmail();
        }).orElse(null);
    }

    public boolean isEnabled(Long userId) {
        String val = redisTemplate.opsForValue().get(USER_ENABLED_KEY + userId);
        if (val != null) return Boolean.parseBoolean(val);

        // Fall back to PostgreSQL
        return chatUserRepository.findById(userId).map(user -> {
            redisTemplate.opsForValue().set(USER_ENABLED_KEY + userId, String.valueOf(user.isEnabled()));
            return user.isEnabled();
        }).orElse(true);
    }

    public void setAdmin(Long userId, boolean admin) {
        chatUserRepository.findById(userId).ifPresent(user -> {
            user.setAdmin(admin);
            user.setUpdatedAt(Instant.now());
            chatUserRepository.save(user);
        });

        if (admin) {
            redisTemplate.opsForSet().add(ADMIN_USERS_KEY, userId.toString());
        } else {
            redisTemplate.opsForSet().remove(ADMIN_USERS_KEY, userId.toString());
        }
    }

    public void addAdminUser(Long userId) {
        setAdmin(userId, true);
    }

    public void removeAdminUser(Long userId) {
        setAdmin(userId, false);
    }

    public Set<Long> getAdminUserIds() {
        var members = redisTemplate.opsForSet().members(ADMIN_USERS_KEY);
        if (members != null && !members.isEmpty()) {
            return members.stream().map(Long::valueOf).collect(Collectors.toSet());
        }

        // Fall back to PostgreSQL
        var admins = chatUserRepository.findByAdminTrue().stream()
                .map(ChatUser::getId)
                .collect(Collectors.toSet());
        admins.forEach(id -> redisTemplate.opsForSet().add(ADMIN_USERS_KEY, id.toString()));
        return admins;
    }

    public Map<String, Object> getUserInfo(Long userId, String fallbackEmail) {
        String email = getEmail(userId);
        if (email == null) email = fallbackEmail;
        return Map.of("id", userId, "email", email != null ? email : "unknown");
    }
}
