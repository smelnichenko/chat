package io.schnappy.chat.service;

import io.schnappy.common.security.PermissionVersionChecker;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class PermissionVersionService implements PermissionVersionChecker {

    private static final String KEY_PREFIX = "user:perm_version:";

    private final StringRedisTemplate redisTemplate;

    public PermissionVersionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public long getVersion(Long userId) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
        return value != null ? Long.parseLong(value) : 0L;
    }
}
