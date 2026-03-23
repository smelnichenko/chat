package io.schnappy.chat.config;

import io.schnappy.chat.entity.ChatUser;
import io.schnappy.chat.repository.ChatUserRepository;
import io.schnappy.chat.security.UserIdResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
public class UserIdResolverConfig {

    @Bean
    public UserIdResolver userIdResolver(ChatUserRepository chatUserRepository) {
        return uuidStr -> {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                return chatUserRepository.findByUuid(uuid).map(ChatUser::getId).orElse(null);
            } catch (IllegalArgumentException _) {
                return null;
            }
        };
    }
}
