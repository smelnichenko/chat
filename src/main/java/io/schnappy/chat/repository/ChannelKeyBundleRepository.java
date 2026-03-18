package io.schnappy.chat.repository;

import io.schnappy.chat.entity.ChannelKeyBundle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelKeyBundleRepository extends JpaRepository<ChannelKeyBundle, Long> {
    Optional<ChannelKeyBundle> findByChannelIdAndUserIdAndKeyVersion(Long channelId, Long userId, int keyVersion);
    List<ChannelKeyBundle> findByChannelIdAndKeyVersion(Long channelId, int keyVersion);
    List<ChannelKeyBundle> findByChannelIdAndUserId(Long channelId, Long userId);
    void deleteByChannelIdAndUserId(Long channelId, Long userId);
    void deleteByChannelId(Long channelId);
}
