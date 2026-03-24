package io.schnappy.chat.repository;

import io.schnappy.chat.entity.ChannelMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelMemberRepository extends JpaRepository<ChannelMember, Long> {
    List<ChannelMember> findByUserUuid(UUID userUuid);
    List<ChannelMember> findByChannelId(Long channelId);
    Optional<ChannelMember> findByChannelIdAndUserUuid(Long channelId, UUID userUuid);
    long countByChannelId(Long channelId);
    boolean existsByChannelIdAndUserUuid(Long channelId, UUID userUuid);
    void deleteByChannelIdAndUserUuid(Long channelId, UUID userUuid);
    void deleteByChannelId(Long channelId);
}
