package io.schnappy.chat.repository;

import io.schnappy.chat.entity.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ChannelRepository extends JpaRepository<Channel, Long> {
    Optional<Channel> findByUuid(UUID uuid);
    Optional<Channel> findByNameAndSystemTrue(String name);
}
