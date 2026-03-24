package io.schnappy.chat.repository;

import io.schnappy.chat.entity.ChannelKeyBundle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelKeyBundleRepository extends JpaRepository<ChannelKeyBundle, Long> {
    Optional<ChannelKeyBundle> findByChannelIdAndUserUuidAndKeyVersion(Long channelId, UUID userUuid, int keyVersion);
    List<ChannelKeyBundle> findByChannelIdAndKeyVersion(Long channelId, int keyVersion);
    List<ChannelKeyBundle> findByChannelIdAndUserUuid(Long channelId, UUID userUuid);
    void deleteByChannelIdAndUserUuid(Long channelId, UUID userUuid);
    void deleteByChannelId(Long channelId);
}
