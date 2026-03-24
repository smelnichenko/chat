package io.schnappy.chat.service;

import io.schnappy.chat.dto.ChatMessageDto;
import io.schnappy.chat.entity.Channel;
import io.schnappy.chat.entity.ChannelMember;
import io.schnappy.chat.kafka.ChatKafkaProducer;
import io.schnappy.chat.repository.ChannelMemberRepository;
import io.schnappy.chat.repository.ChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemChannelService {

    private static final String ADMIN_CHANNEL_NAME = "Admin Notifications";
    private static final UUID SYSTEM_UUID = new UUID(0, 0);

    private final ChannelRepository channelRepository;
    private final ChannelMemberRepository memberRepository;
    private final ChatKafkaProducer kafkaProducer;
    private final UserCacheService userCacheService;

    @Transactional
    public Channel getOrCreateAdminChannel() {
        return channelRepository.findByNameAndSystemTrue(ADMIN_CHANNEL_NAME)
            .orElseGet(this::createAdminChannel);
    }

    private Channel createAdminChannel() {
        Set<UUID> adminUserUuids = userCacheService.getAdminUserUuids();
        if (adminUserUuids.isEmpty()) {
            throw new IllegalStateException("No admin users found to create system channel");
        }
        UUID creatorUuid = adminUserUuids.iterator().next();

        var channel = new Channel();
        channel.setName(ADMIN_CHANNEL_NAME);
        channel.setSystem(true);
        channel.setCreatedByUuid(creatorUuid);
        channel = channelRepository.save(channel);

        for (UUID userUuid : adminUserUuids) {
            addMemberIfAbsent(channel.getId(), userUuid);
        }

        log.info("Created system channel '{}' with {} members", ADMIN_CHANNEL_NAME, adminUserUuids.size());
        return channel;
    }

    @Transactional
    public void syncAdminChannelMembers() {
        channelRepository.findByNameAndSystemTrue(ADMIN_CHANNEL_NAME).ifPresent(channel -> {
            Set<UUID> adminUserUuids = userCacheService.getAdminUserUuids();
            var currentMembers = memberRepository.findByChannelId(channel.getId());
            var currentMemberUuids = new HashSet<>(currentMembers.stream()
                .map(ChannelMember::getUserUuid).toList());

            for (UUID userUuid : adminUserUuids) {
                if (!currentMemberUuids.contains(userUuid)) {
                    addMemberIfAbsent(channel.getId(), userUuid);
                    log.info("Added user {} to admin channel", userUuid);
                }
            }
        });
    }

    public void postSystemMessage(Long channelId, String content, String metadata) {
        var message = ChatMessageDto.builder()
            .messageId(UUID.randomUUID().toString())
            .channelId(channelId)
            .userUuid(SYSTEM_UUID)
            .username("System")
            .content(content)
            .createdAt(Instant.now())
            .messageType("SYSTEM")
            .metadata(metadata)
            .build();

        kafkaProducer.sendMessage(message);
    }

    private void addMemberIfAbsent(Long channelId, UUID userUuid) {
        if (!memberRepository.existsByChannelIdAndUserUuid(channelId, userUuid)) {
            var member = new ChannelMember();
            member.setChannelId(channelId);
            member.setUserUuid(userUuid);
            memberRepository.save(member);
        }
    }
}
