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
        Set<Long> adminUserIds = userCacheService.getAdminUserIds();
        if (adminUserIds.isEmpty()) {
            throw new IllegalStateException("No admin users found to create system channel");
        }
        Long creatorId = adminUserIds.iterator().next();

        var channel = new Channel();
        channel.setName(ADMIN_CHANNEL_NAME);
        channel.setSystem(true);
        channel.setCreatedBy(creatorId);
        channel = channelRepository.save(channel);

        for (Long userId : adminUserIds) {
            addMemberIfAbsent(channel.getId(), userId);
        }

        log.info("Created system channel '{}' with {} members", ADMIN_CHANNEL_NAME, adminUserIds.size());
        return channel;
    }

    @Transactional
    public void syncAdminChannelMembers() {
        channelRepository.findByNameAndSystemTrue(ADMIN_CHANNEL_NAME).ifPresent(channel -> {
            Set<Long> adminUserIds = userCacheService.getAdminUserIds();
            var currentMembers = memberRepository.findByChannelId(channel.getId());
            var currentMemberIds = new HashSet<>(currentMembers.stream()
                .map(ChannelMember::getUserId).toList());

            for (Long userId : adminUserIds) {
                if (!currentMemberIds.contains(userId)) {
                    addMemberIfAbsent(channel.getId(), userId);
                    log.info("Added user {} to admin channel", userId);
                }
            }
        });
    }

    public void postSystemMessage(Long channelId, String content, String metadata) {
        var message = ChatMessageDto.builder()
            .messageId(UUID.randomUUID().toString())
            .channelId(channelId)
            .userId(0L)
            .username("System")
            .content(content)
            .createdAt(Instant.now())
            .messageType("SYSTEM")
            .metadata(metadata)
            .build();

        kafkaProducer.sendMessage(message);
    }

    private void addMemberIfAbsent(Long channelId, Long userId) {
        if (!memberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            var member = new ChannelMember();
            member.setChannelId(channelId);
            member.setUserId(userId);
            memberRepository.save(member);
        }
    }
}
