package io.schnappy.chat.repository;

import io.schnappy.chat.entity.ChatUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatUserRepository extends JpaRepository<ChatUser, Long> {

    List<ChatUser> findByEnabledTrue();

    List<ChatUser> findByAdminTrue();
}
