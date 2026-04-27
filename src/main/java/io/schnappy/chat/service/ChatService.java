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
import io.schnappy.chat.kafka.EventEnvelope;
import io.schnappy.chat.kafka.EventEnvelopeProducer;
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
    private final EventEnvelopeProducer envelopeProducer;
    private final UserCacheService userCacheService;
    private final UserKeysRepository userKeysRepository;
    private final ChannelKeyBundleRepository keyBundleRepository;

    @Transactional
    public Channel createChannel(CreateChannelRequest request, UUID userUuid) {
        var channel = new Channel();
        channel.setName(request.name());
        channel.setEncrypted(request.encrypted() != null && request.encrypted());
        channel.setCreatedByUuid(userUuid);
        channel = channelRepository.save(channel);

        var member = new ChannelMember();
        member.setChannelId(channel.getId());
        member.setUserUuid(userUuid);
        memberRepository.save(member);

        return channel;
    }

    public List<Channel> getUserChannels(UUID userUuid) {
        var memberships = memberRepository.findByUserUuid(userUuid);
        var channelIds = memberships.stream().map(ChannelMember::getChannelId).toList();
        return channelRepository.findAllById(channelIds);
    }

    public List<ChannelDto> getAllChannelsWithMembership(UUID userUuid) {
        var channels = getUserChannels(userUuid);

        return channels.stream()
            .map(ch -> ChannelDto.builder()
                .id(ch.getId())
                .name(ch.getName())
                .createdAt(ch.getCreatedAt().toString())
                .memberCount((int) memberRepository.countByChannelId(ch.getId()))
                .joined(true)
                .isOwner(ch.getCreatedByUuid().equals(userUuid))
                .encrypted(ch.isEncrypted())
                .currentKeyVersion(ch.getCurrentKeyVersion())
                .isSystem(ch.isSystem())
                .build()
            ).toList();
    }

    @Transactional
    public void inviteToChannel(Long channelId, UUID invitedUserUuid, UUID inviterUuid) {
        var channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException(CHANNEL_NOT_FOUND));
        if (!channel.getCreatedByUuid().equals(inviterUuid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the channel creator can invite");
        }
        if (memberRepository.existsByChannelIdAndUserUuid(channelId, invitedUserUuid)) {
            return;
        }
        // Validate user exists via cache (populated from user.events)
        if (userCacheService.getEmail(invitedUserUuid) == null) {
            throw new IllegalArgumentException("User not found");
        }
        var member = new ChannelMember();
        member.setChannelId(channelId);
        member.setUserUuid(invitedUserUuid);
        memberRepository.save(member);
    }

    public List<Map<String, Object>> getChatUsers(UUID excludeUserUuid) {
        // Return all known users from cache (populated from user.events)
        return memberRepository.findAll().stream()
            .map(ChannelMember::getUserUuid)
            .distinct()
            .filter(uid -> !uid.equals(excludeUserUuid))
            .filter(uid -> userCacheService.isEnabled(uid))
            .map(uid -> userCacheService.getUserInfo(uid, null))
            .toList();
    }

    @Transactional
    public void deleteChannel(Long channelId, UUID userUuid) {
        var channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException(CHANNEL_NOT_FOUND));
        if (channel.isSystem()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System channels cannot be deleted");
        }
        if (!channel.getCreatedByUuid().equals(userUuid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the channel creator can delete");
        }
        messageRepository.deleteMessagesByChannel(channelId, channel.getCreatedAt());
        keyBundleRepository.deleteByChannelId(channelId);
        memberRepository.deleteByChannelId(channelId);
        channelRepository.delete(channel);
    }

    @Transactional
    public void kickFromChannel(Long channelId, UUID targetUserUuid, UUID requesterUuid) {
        var channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException(CHANNEL_NOT_FOUND));
        if (channel.isSystem()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot kick members from system channels");
        }
        if (!channel.getCreatedByUuid().equals(requesterUuid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the channel creator can kick members");
        }
        if (targetUserUuid.equals(requesterUuid)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot kick yourself");
        }
        keyBundleRepository.deleteByChannelIdAndUserUuid(channelId, targetUserUuid);
        memberRepository.deleteByChannelIdAndUserUuid(channelId, targetUserUuid);
    }

    @Transactional
    public void leaveChannel(Long channelId, UUID userUuid) {
        var channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException(CHANNEL_NOT_FOUND));
        if (channel.isSystem()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot leave system channels");
        }
        if (channel.getCreatedByUuid().equals(userUuid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Channel creator cannot leave — delete the channel instead");
        }
        keyBundleRepository.deleteByChannelIdAndUserUuid(channelId, userUuid);
        memberRepository.deleteByChannelIdAndUserUuid(channelId, userUuid);
    }

    public List<Map<String, Object>> getChannelMembers(Long channelId) {
        var members = memberRepository.findByChannelId(channelId);
        return members.stream()
            .map(m -> {
                String email = userCacheService.getEmail(m.getUserUuid());
                return Map.<String, Object>of(
                    "id", m.getUserUuid().toString(),
                    "email", email != null ? email : "unknown",
                    "joinedAt", m.getJoinedAt().toString()
                );
            }).toList();
    }

    public boolean isMember(Long channelId, UUID userUuid) {
        return memberRepository.existsByChannelIdAndUserUuid(channelId, userUuid);
    }

    public ChatMessageDto sendMessage(Long channelId, SendMessageRequest request, UUID userUuid, String username) {
        var message = ChatMessageDto.builder()
            .messageId(UUID.randomUUID().toString())
            .channelId(channelId)
            .userUuid(userUuid)
            .username(username)
            .content(request.content())
            .parentMessageId(request.parentMessageId())
            .createdAt(Instant.now())
            .keyVersion(request.keyVersion())
            .build();

        kafkaProducer.sendMessage(message);
        publishEnvelope("message.sent", channelId, userUuid, message);
        return message;
    }

    private void publishEnvelope(String type, Long channelId, UUID actor, Object payload) {
        envelopeProducer.publish(
            "chat:room:" + channelId,
            String.valueOf(channelId),
            EventEnvelope.of(type, "room:" + channelId, actor.toString(), payload)
        );
    }

    public void editMessage(Long channelId, String messageId, String newContent, UUID userUuid) {
        var messages = messageRepository.getRecentMessages(channelId, 100);
        var original = messages.stream()
            .filter(m -> m.getMessageId().equals(messageId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));

        if (!original.getUserUuid().equals(userUuid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Can only edit your own messages");
        }

        String bucket = ScyllaMessageRepository.bucketForDate(original.getCreatedAt());
        messageRepository.saveEdit(channelId, bucket, UUID.fromString(messageId), userUuid, newContent, original.getHash());
        publishEnvelope("message.edited", channelId, userUuid,
            Map.of("messageId", messageId, "editedContent", newContent));
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
    public void updateLastRead(Long channelId, UUID userUuid) {
        memberRepository.findByChannelIdAndUserUuid(channelId, userUuid)
            .ifPresent(member -> {
                member.setLastReadAt(Instant.now());
                memberRepository.save(member);
            });
    }

    // --- E2E Encryption: Key Management ---

    public UserKeysDto getUserKeys(UUID userUuid) {
        var keys = userKeysRepository.findByUserUuid(userUuid).orElse(null);
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
    public UserKeysDto uploadUserKeys(UploadKeysRequest request, UUID userUuid) {
        if (userKeysRepository.existsByUserUuid(userUuid)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Keys already exist");
        }
        var keys = new UserKeys();
        keys.setUserUuid(userUuid);
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
    public void updateUserKeys(UploadKeysRequest request, UUID userUuid) {
        var keys = userKeysRepository.findByUserUuid(userUuid)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No keys found"));
        keys.setPublicKey(request.publicKey());
        keys.setEncryptedPrivateKey(request.encryptedPrivateKey());
        keys.setPbkdf2Salt(request.pbkdf2Salt());
        keys.setPbkdf2Iterations(request.pbkdf2Iterations());
        keys.setKeyVersion(keys.getKeyVersion() + 1);
        keys.setUpdatedAt(Instant.now());
        userKeysRepository.save(keys);
    }

    public List<Map<String, Object>> getPublicKeys(List<UUID> userUuids) {
        return userKeysRepository.findByUserUuidIn(userUuids).stream()
            .map(k -> Map.<String, Object>of(
                "userUuid", k.getUserUuid().toString(),
                "publicKey", k.getPublicKey(),
                "keyVersion", k.getKeyVersion()
            ))
            .toList();
    }

    public ChannelKeyBundleDto getChannelKeyBundle(Long channelId, UUID userUuid) {
        var channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException(CHANNEL_NOT_FOUND));
        var bundle = keyBundleRepository
            .findByChannelIdAndUserUuidAndKeyVersion(channelId, userUuid, channel.getCurrentKeyVersion())
            .orElse(null);
        if (bundle == null) return null;
        return ChannelKeyBundleDto.builder()
            .userUuid(bundle.getUserUuid())
            .keyVersion(bundle.getKeyVersion())
            .encryptedChannelKey(bundle.getEncryptedChannelKey())
            .wrapperPublicKey(bundle.getWrapperPublicKey())
            .build();
    }

    public List<ChannelKeyBundleDto> getChannelKeyBundles(Long channelId, UUID userUuid, Integer keyVersion) {
        if (!memberRepository.existsByChannelIdAndUserUuid(channelId, userUuid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member");
        }
        List<ChannelKeyBundle> bundles;
        if (keyVersion != null) {
            bundles = keyBundleRepository.findByChannelIdAndUserUuid(channelId, userUuid).stream()
                .filter(b -> b.getKeyVersion() == keyVersion)
                .toList();
        } else {
            bundles = keyBundleRepository.findByChannelIdAndUserUuid(channelId, userUuid);
        }
        return bundles.stream()
            .map(b -> ChannelKeyBundleDto.builder()
                .userUuid(b.getUserUuid())
                .keyVersion(b.getKeyVersion())
                .encryptedChannelKey(b.getEncryptedChannelKey())
                .wrapperPublicKey(b.getWrapperPublicKey())
                .build())
            .toList();
    }

    @Transactional
    public void setChannelKeys(Long channelId, SetChannelKeysRequest request, UUID userUuid) {
        var channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException(CHANNEL_NOT_FOUND));
        if (!channel.getCreatedByUuid().equals(userUuid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the channel creator can set keys");
        }
        int version = channel.getCurrentKeyVersion() == 0 ? 1 : channel.getCurrentKeyVersion();
        for (var bundle : request.bundles()) {
            if (memberRepository.existsByChannelIdAndUserUuid(channelId, bundle.userUuid())
                    && keyBundleRepository.findByChannelIdAndUserUuidAndKeyVersion(channelId, bundle.userUuid(), version).isEmpty()) {
                var keyBundle = new ChannelKeyBundle();
                keyBundle.setChannelId(channelId);
                keyBundle.setUserUuid(bundle.userUuid());
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
    public int rotateChannelKeys(Long channelId, SetChannelKeysRequest request, UUID userUuid) {
        var channel = channelRepository.findById(channelId)
            .orElseThrow(() -> new IllegalArgumentException(CHANNEL_NOT_FOUND));
        if (!channel.getCreatedByUuid().equals(userUuid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the channel creator can rotate keys");
        }
        int newVersion = channel.getCurrentKeyVersion() + 1;
        for (var bundle : request.bundles()) {
            if (!memberRepository.existsByChannelIdAndUserUuid(channelId, bundle.userUuid())) {
                continue;
            }
            var keyBundle = new ChannelKeyBundle();
            keyBundle.setChannelId(channelId);
            keyBundle.setUserUuid(bundle.userUuid());
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
