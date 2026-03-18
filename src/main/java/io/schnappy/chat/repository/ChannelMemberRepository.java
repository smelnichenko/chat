package io.schnappy.chat.repository;

import io.schnappy.chat.entity.ChannelMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelMemberRepository extends JpaRepository<ChannelMember, Long> {
    List<ChannelMember> findByUserId(Long userId);
    List<ChannelMember> findByChannelId(Long channelId);
    Optional<ChannelMember> findByChannelIdAndUserId(Long channelId, Long userId);
    long countByChannelId(Long channelId);
    boolean existsByChannelIdAndUserId(Long channelId, Long userId);
    void deleteByChannelIdAndUserId(Long channelId, Long userId);
    void deleteByChannelId(Long channelId);
}
