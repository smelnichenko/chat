package io.schnappy.chat.repository;

import io.schnappy.chat.entity.ChatUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatUserRepository extends JpaRepository<ChatUser, Long> {

    List<ChatUser> findByEnabledTrue();

    List<ChatUser> findByAdminTrue();

    Optional<ChatUser> findByUuid(UUID uuid);
}
