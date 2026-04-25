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
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * User cache backed by PostgreSQL (chat_users table) with Valkey as read-through cache.
 * Populated from Kafka user.events and gateway headers.
 * All keys use UUID as the user identifier.
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

    public void cacheUser(UUID uuid, String email, boolean enabled) {
        // Write to PostgreSQL (source of truth)
        var user = chatUserRepository.findByUuid(uuid).orElseGet(() -> {
            var u = new ChatUser();
            u.setUuid(uuid);
            return u;
        });
        user.setEmail(email);
        user.setEnabled(enabled);
        user.setUpdatedAt(Instant.now());
        chatUserRepository.save(user);

        // Write to Valkey (cache)
        String key = uuid.toString();
        redisTemplate.opsForValue().set(USER_EMAIL_KEY + key, email);
        redisTemplate.opsForValue().set(USER_ENABLED_KEY + key, String.valueOf(enabled));
    }

    public String getEmail(UUID uuid) {
        String key = uuid.toString();
        // Try Valkey first
        String email = redisTemplate.opsForValue().get(USER_EMAIL_KEY + key);
        if (email != null) return email;

        // Fall back to PostgreSQL
        return chatUserRepository.findByUuid(uuid).map(user -> {
            // Populate Redis cache
            redisTemplate.opsForValue().set(USER_EMAIL_KEY + key, user.getEmail());
            redisTemplate.opsForValue().set(USER_ENABLED_KEY + key, String.valueOf(user.isEnabled()));
            return user.getEmail();
        }).orElse(null);
    }

    public boolean isEnabled(UUID uuid) {
        String key = uuid.toString();
        String val = redisTemplate.opsForValue().get(USER_ENABLED_KEY + key);
        if (val != null) return Boolean.parseBoolean(val);

        // Fall back to PostgreSQL
        return chatUserRepository.findByUuid(uuid).map(user -> {
            redisTemplate.opsForValue().set(USER_ENABLED_KEY + key, String.valueOf(user.isEnabled()));
            return user.isEnabled();
        }).orElse(true);
    }

    public void setAdmin(UUID uuid, boolean admin) {
        chatUserRepository.findByUuid(uuid).ifPresent(user -> {
            user.setAdmin(admin);
            user.setUpdatedAt(Instant.now());
            chatUserRepository.save(user);
        });

        if (admin) {
            redisTemplate.opsForSet().add(ADMIN_USERS_KEY, uuid.toString());
        } else {
            redisTemplate.opsForSet().remove(ADMIN_USERS_KEY, uuid.toString());
        }
    }

    public void addAdminUser(UUID uuid) {
        setAdmin(uuid, true);
    }

    public void removeAdminUser(UUID uuid) {
        setAdmin(uuid, false);
    }

    public Set<UUID> getAdminUserUuids() {
        var members = redisTemplate.opsForSet().members(ADMIN_USERS_KEY);
        if (members != null && !members.isEmpty()) {
            return members.stream().map(UUID::fromString).collect(Collectors.toSet());
        }

        // Fall back to PostgreSQL
        var admins = chatUserRepository.findByAdminTrue().stream()
                .filter(u -> u.getUuid() != null)
                .map(ChatUser::getUuid)
                .collect(Collectors.toSet());
        admins.forEach(id -> redisTemplate.opsForSet().add(ADMIN_USERS_KEY, id.toString()));
        return admins;
    }

    public Map<String, Object> getUserInfo(UUID uuid, String fallbackEmail) {
        String email = getEmail(uuid);
        if (email == null) email = fallbackEmail;
        return Map.of("id", uuid.toString(), "email", email != null ? email : "unknown");
    }
}
