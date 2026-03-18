package io.schnappy.chat.repository;

import io.schnappy.chat.entity.UserKeys;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserKeysRepository extends JpaRepository<UserKeys, Long> {
    Optional<UserKeys> findByUserId(Long userId);
    boolean existsByUserId(Long userId);
    List<UserKeys> findByUserIdIn(List<Long> userIds);
}
