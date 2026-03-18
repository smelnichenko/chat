package io.schnappy.chat.service;

import io.schnappy.chat.dto.ChannelDto;
import io.schnappy.chat.dto.ChannelKeyBundleDto;
import io.schnappy.chat.dto.ChatMessageDto;
import io.schnappy.chat.dto.CreateChannelRequest;
import io.schnappy.chat.dto.SendMessageRequest;
import io.schnappy.chat.dto.SetChannelKeysRequest;
import io.schnappy.chat.dto.UploadKeysRequest;
import io.schnappy.chat.dto.UserKeysDto;
import io.schnappy.chat.entity.Channel;
import io.schnappy.chat.entity.ChannelKeyBundle;
import io.schnappy.chat.entity.ChannelMember;
import io.schnappy.chat.entity.UserKeys;
import io.schnappy.chat.kafka.ChatKafkaProducer;
import io.schnappy.chat.repository.ChannelKeyBundleRepository;
import io.schnappy.chat.repository.ChannelMemberRepository;
import io.schnappy.chat.repository.ChannelRepository;
import io.schnappy.chat.repository.ScyllaMessageRepository;
import io.schnappy.chat.repository.UserKeysRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String CHANNEL_NOT_FOUND = "Channel not found";

    private final ChannelRepository channelRepository;
    private final ChannelMemberRepository memberRepository;
    private final ScyllaMessageRepository messageRepository;
    private final ChatKafkaProducer kafkaProducer;
    private final UserCacheService userCacheService;
    private final UserKeysRepository userKeysRepository;
    private final ChannelKeyBundleRepository keyBundleRepository;

    @Transactional
    public Channel createChannel(CreateChannelRequest request, Long userId) {
        var channel = new Channel();
        channel.setName(request.name());
        channel.setEncrypted(request.encrypted() != null && request.encrypted());
        channel.setCreatedBy(userId);
        channel = channelRepository.save(channel);

        var member = new ChannelMember();
        member.setChannelId(channel.getId());
        member.setUserId(userId);
        memberRepository.save(member);

        return channel;
    }

    public List<Channel> getUserChannels(Long userId) {
        var memberships = memberRepository.findByUserId(userId);
        var channelIds = memberships.stream().map(ChannelMember::getChannelId).toList();
        return channelRepository.findAllById(channelIds);
    }

    public List<ChannelDto> getAllChannelsWithMembership(Long userId) {
        var channels = getUserChannels(userId);

        return channels.stream()
            .map(ch -> ChannelDto.builder()
                .id(ch.getId())
                .name(ch.getName())
                .createdAt(ch.getCreatedAt().toString())
                .memberCount((int) memberRepository.countByChannelId(ch.getId()))
                .joined(true)
                .isOwner(ch.getCreatedBy().equals(userId))
                .encrypted(ch.isEncrypted())
                .currentKeyVersion(ch.getCurrentKeyVersion())
                .isSystem(ch.isSystem())
                .build()
            ).toList();
    }

    @Transactional
    public void inviteToChannel(Long channelId, Long invitedUserId, Long inviterUserId) {
        var channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException(CHANNEL_NOT_FOUND));
        if (!channel.getCreatedBy().equals(inviterUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the channel creator can invite");
        }
        if (memberRepository.existsByChannelIdAndUserId(channelId, invitedUserId)) {
            return;
        }
        // Validate user exists via cache (populated from user.events)
        if (userCacheService.getEmail(invitedUserId) == null) {
            throw new IllegalArgumentException("User not found");
        }
        var member = new ChannelMember();
        member.setChannelId(channelId);
        member.setUserId(invitedUserId);
        memberRepository.save(member);
    }

    public List<Map<String, Object>> getChatUsers(Long excludeUserId) {
        // Return all known users from cache (populated from user.events)
        // In the microservice, we don't have direct DB access to users table
        // This returns users that have been seen via user.events
        return memberRepository.findAll().stream()
            .map(ChannelMember::getUserId)
            .distinct()
            .filter(uid -> !uid.equals(excludeUserId))
            .filter(uid -> userCacheService.isEnabled(uid))
            .map(uid -> userCacheService.getUserInfo(uid, null))
            .toList();
    }

    @Transactional
    public void deleteChannel(Long channelId, Long userId) {
        var channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException(CHANNEL_NOT_FOUND));
        if (channel.isSystem()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System channels cannot be deleted");
        }
        if (!channel.getCreatedBy().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the channel creator can delete");
        }
        messageRepository.deleteMessagesByChannel(channelId, channel.getCreatedAt());
        keyBundleRepository.deleteByChannelId(channelId);
        memberRepository.deleteByChannelId(channelId);
        channelRepository.delete(channel);
    }

    @Transactional
    public void kickFromChannel(Long channelId, Long targetUserId, Long requesterId) {
        var channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException(CHANNEL_NOT_FOUND));
        if (channel.isSystem()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot kick members from system channels");
        }
        if (!channel.getCreatedBy().equals(requesterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the channel creator can kick members");
        }
        if (targetUserId.equals(requesterId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot kick yourself");
        }
        keyBundleRepository.deleteByChannelIdAndUserId(channelId, targetUserId);
        memberRepository.deleteByChannelIdAndUserId(channelId, targetUserId);
    }

    @Transactional
    public void leaveChannel(Long channelId, Long userId) {
        var channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException(CHANNEL_NOT_FOUND));
        if (channel.isSystem()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot leave system channels");
        }
        if (channel.getCreatedBy().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Channel creator cannot leave — delete the channel instead");
        }
        keyBundleRepository.deleteByChannelIdAndUserId(channelId, userId);
        memberRepository.deleteByChannelIdAndUserId(channelId, userId);
    }

    public List<Map<String, Object>> getChannelMembers(Long channelId) {
        var members = memberRepository.findByChannelId(channelId);
        return members.stream()
            .map(m -> {
                String email = userCacheService.getEmail(m.getUserId());
                return Map.<String, Object>of(
                    "id", m.getUserId(),
                    "email", email != null ? email : "unknown",
                    "joinedAt", m.getJoinedAt().toString()
                );
            }).toList();
    }

    public boolean isMember(Long channelId, Long userId) {
        return memberRepository.existsByChannelIdAndUserId(channelId, userId);
    }

    public ChatMessageDto sendMessage(Long channelId, SendMessageRequest request, Long userId, String username) {
        var message = ChatMessageDto.builder()
            .messageId(UUID.randomUUID().toString())
            .channelId(channelId)
            .userId(userId)
            .username(username)
            .content(request.content())
            .parentMessageId(request.parentMessageId())
            .createdAt(Instant.now())
            .keyVersion(request.keyVersion())
            .build();

        kafkaProducer.sendMessage(message);
        return message;
    }

    public void editMessage(Long channelId, String messageId, String newContent, Long userId) {
        var messages = messageRepository.getRecentMessages(channelId, 100);
        var original = messages.stream()
            .filter(m -> m.getMessageId().equals(messageId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));

        if (original.getUserId() != userId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Can only edit your own messages");
        }

        String bucket = ScyllaMessageRepository.bucketForDate(original.getCreatedAt());
        messageRepository.saveEdit(channelId, bucket, UUID.fromString(messageId), userId, newContent, original.getHash());
    }

    public List<ScyllaMessageRepository.EditRecord> getMessageEdits(Long channelId, String messageId) {
        UUID msgUuid = UUID.fromString(messageId);
        Instant msgTime = Instant.ofEpochMilli(com.datastax.oss.driver.api.core.uuid.Uuids.unixTimestamp(msgUuid));
        String bucket = ScyllaMessageRepository.bucketForDate(msgTime);
        return messageRepository.getEdits(channelId, bucket, msgUuid);
    }

    public ScyllaMessageRepository.ChainVerification verifyChain(Long channelId) {
        var channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException(CHANNEL_NOT_FOUND));
        return messageRepository.verifyChain(channelId, channel.getCreatedAt());
    }

    public List<ChatMessageDto> getMessages(Long channelId, int limit) {
        return messageRepository.getRecentMessages(channelId, limit);
    }

    public List<ChannelMember> getMembers(Long channelId) {
        return memberRepository.findByChannelId(channelId);
    }

    @Transactional
    public void updateLastRead(Long channelId, Long userId) {
        memberRepository.findByChannelIdAndUserId(channelId, userId)
            .ifPresent(member -> {
                member.setLastReadAt(Instant.now());
                memberRepository.save(member);
            });
    }

    // --- E2E Encryption: Key Management ---

    public UserKeysDto getUserKeys(Long userId) {
        var keys = userKeysRepository.findByUserId(userId).orElse(null);
        if (keys == null) return null;
        return UserKeysDto.builder()
            .publicKey(keys.getPublicKey())
            .encryptedPrivateKey(keys.getEncryptedPrivateKey())
            .pbkdf2Salt(keys.getPbkdf2Salt())
            .pbkdf2Iterations(keys.getPbkdf2Iterations())
            .keyVersion(keys.getKeyVersion())
            .build();
    }

    @Transactional
    public UserKeysDto uploadUserKeys(UploadKeysRequest request, Long userId) {
        if (userKeysRepository.existsByUserId(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Keys already exist");
        }
        var keys = new UserKeys();
        keys.setUserId(userId);
        keys.setPublicKey(request.publicKey());
        keys.setEncryptedPrivateKey(request.encryptedPrivateKey());
        keys.setPbkdf2Salt(request.pbkdf2Salt());
        keys.setPbkdf2Iterations(request.pbkdf2Iterations());
        userKeysRepository.save(keys);
        return UserKeysDto.builder()
            .publicKey(keys.getPublicKey())
            .encryptedPrivateKey(keys.getEncryptedPrivateKey())
            .pbkdf2Salt(keys.getPbkdf2Salt())
            .pbkdf2Iterations(keys.getPbkdf2Iterations())
            .keyVersion(keys.getKeyVersion())
            .build();
    }

    @Transactional
    public void updateUserKeys(UploadKeysRequest request, Long userId) {
        var keys = userKeysRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No keys found"));
        keys.setPublicKey(request.publicKey());
        keys.setEncryptedPrivateKey(request.encryptedPrivateKey());
        keys.setPbkdf2Salt(request.pbkdf2Salt());
        keys.setPbkdf2Iterations(request.pbkdf2Iterations());
        keys.setKeyVersion(keys.getKeyVersion() + 1);
        keys.setUpdatedAt(Instant.now());
        userKeysRepository.save(keys);
    }

    public List<Map<String, Object>> getPublicKeys(List<Long> userIds) {
        return userKeysRepository.findByUserIdIn(userIds).stream()
            .map(k -> Map.<String, Object>of(
                "userId", k.getUserId(),
                "publicKey", k.getPublicKey(),
                "keyVersion", k.getKeyVersion()
            ))
            .toList();
    }

    public ChannelKeyBundleDto getChannelKeyBundle(Long channelId, Long userId) {
        var channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException(CHANNEL_NOT_FOUND));
        var bundle = keyBundleRepository
            .findByChannelIdAndUserIdAndKeyVersion(channelId, userId, channel.getCurrentKeyVersion())
            .orElse(null);
        if (bundle == null) return null;
        return ChannelKeyBundleDto.builder()
            .userId(bundle.getUserId())
            .keyVersion(bundle.getKeyVersion())
            .encryptedChannelKey(bundle.getEncryptedChannelKey())
            .wrapperPublicKey(bundle.getWrapperPublicKey())
            .build();
    }

    public List<ChannelKeyBundleDto> getChannelKeyBundles(Long channelId, Long userId, Integer keyVersion) {
        if (!memberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member");
        }
        List<ChannelKeyBundle> bundles;
        if (keyVersion != null) {
            bundles = keyBundleRepository.findByChannelIdAndUserId(channelId, userId).stream()
                .filter(b -> b.getKeyVersion() == keyVersion)
                .toList();
        } else {
            bundles = keyBundleRepository.findByChannelIdAndUserId(channelId, userId);
        }
        return bundles.stream()
            .map(b -> ChannelKeyBundleDto.builder()
                .userId(b.getUserId())
                .keyVersion(b.getKeyVersion())
                .encryptedChannelKey(b.getEncryptedChannelKey())
                .wrapperPublicKey(b.getWrapperPublicKey())
                .build())
            .toList();
    }

    @Transactional
    public void setChannelKeys(Long channelId, SetChannelKeysRequest request, Long userId) {
        var channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException(CHANNEL_NOT_FOUND));
        if (!channel.getCreatedBy().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the channel creator can set keys");
        }
        int version = channel.getCurrentKeyVersion() == 0 ? 1 : channel.getCurrentKeyVersion();
        for (var bundle : request.bundles()) {
            if (memberRepository.existsByChannelIdAndUserId(channelId, bundle.userId())
                    && keyBundleRepository.findByChannelIdAndUserIdAndKeyVersion(channelId, bundle.userId(), version).isEmpty()) {
                var keyBundle = new ChannelKeyBundle();
                keyBundle.setChannelId(channelId);
                keyBundle.setUserId(bundle.userId());
                keyBundle.setKeyVersion(version);
                keyBundle.setEncryptedChannelKey(bundle.encryptedChannelKey());
                keyBundle.setWrapperPublicKey(bundle.wrapperPublicKey());
                keyBundleRepository.save(keyBundle);
            }
        }
        if (channel.getCurrentKeyVersion() == 0) {
            channel.setCurrentKeyVersion(1);
            channelRepository.save(channel);
        }
    }

    @Transactional
    public int rotateChannelKeys(Long channelId, SetChannelKeysRequest request, Long userId) {
        var channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException(CHANNEL_NOT_FOUND));
        if (!channel.getCreatedBy().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the channel creator can rotate keys");
        }
        int newVersion = channel.getCurrentKeyVersion() + 1;
        for (var bundle : request.bundles()) {
            if (!memberRepository.existsByChannelIdAndUserId(channelId, bundle.userId())) {
                continue;
            }
            var keyBundle = new ChannelKeyBundle();
            keyBundle.setChannelId(channelId);
            keyBundle.setUserId(bundle.userId());
            keyBundle.setKeyVersion(newVersion);
            keyBundle.setEncryptedChannelKey(bundle.encryptedChannelKey());
            keyBundle.setWrapperPublicKey(bundle.wrapperPublicKey());
            keyBundleRepository.save(keyBundle);
        }
        channel.setCurrentKeyVersion(newVersion);
        channelRepository.save(channel);
        return newVersion;
    }
}
