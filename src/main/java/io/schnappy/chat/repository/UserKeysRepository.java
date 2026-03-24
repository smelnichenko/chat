package io.schnappy.chat.repository;

import io.schnappy.chat.entity.UserKeys;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserKeysRepository extends JpaRepository<UserKeys, Long> {
    Optional<UserKeys> findByUserUuid(UUID userUuid);
    boolean existsByUserUuid(UUID userUuid);
    List<UserKeys> findByUserUuidIn(List<UUID> userUuids);
}
