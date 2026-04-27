package io.schnappy.chat.service;

import io.schnappy.chat.dto.ChatMessageDto;
import io.schnappy.chat.dto.CreateChannelRequest;
import io.schnappy.chat.dto.SendMessageRequest;
import io.schnappy.chat.dto.SetChannelKeysRequest;
import io.schnappy.chat.dto.UploadKeysRequest;
import io.schnappy.chat.entity.Channel;
import io.schnappy.chat.entity.ChannelKeyBundle;
import io.schnappy.chat.entity.ChannelMember;
import io.schnappy.chat.entity.UserKeys;
import io.schnappy.chat.kafka.ChatKafkaProducer;
import io.schnappy.chat.kafka.EventEnvelopeProducer;
import io.schnappy.chat.repository.ChannelKeyBundleRepository;
import io.schnappy.chat.repository.ChannelMemberRepository;
import io.schnappy.chat.repository.ChannelRepository;
import io.schnappy.chat.repository.ScyllaMessageRepository;
import io.schnappy.chat.repository.UserKeysRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final UUID USER_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID OTHER_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440099");

    @Mock private ChannelRepository channelRepository;
    @Mock private ChannelMemberRepository memberRepository;
    @Mock private ScyllaMessageRepository messageRepository;
    @Mock private ChatKafkaProducer kafkaProducer;
    @Mock private EventEnvelopeProducer envelopeProducer;
    @Mock private UserCacheService userCacheService;
    @Mock private UserKeysRepository userKeysRepository;
    @Mock private ChannelKeyBundleRepository keyBundleRepository;

    @InjectMocks
    private ChatService chatService;

    private Channel channel;

    @BeforeEach
    void setUp() {
        channel = new Channel();
        channel.setId(1L);
        channel.setName("general");
        channel.setCreatedByUuid(USER_UUID);
        channel.setSystem(false);
        channel.setEncrypted(false);
        channel.setCurrentKeyVersion(0);
    }

    // --- createChannel ---

    @Test
    void createChannel_savesChannelAndAddsCreatorAsMember() {
        var request = new CreateChannelRequest("general", false);
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> {
            Channel c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });
        when(memberRepository.save(any(ChannelMember.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = chatService.createChannel(request, USER_UUID);

        assertThat(result.getName()).isEqualTo("general");
        assertThat(result.getCreatedByUuid()).isEqualTo(USER_UUID);

        var memberCaptor = ArgumentCaptor.forClass(ChannelMember.class);
        verify(memberRepository).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getUserUuid()).isEqualTo(USER_UUID);
        assertThat(memberCaptor.getValue().getChannelId()).isEqualTo(1L);
    }

    // --- getUserChannels ---

    @Test
    void getUserChannels_returnsChannelsForMemberships() {
        var member = new ChannelMember();
        member.setChannelId(1L);
        member.setUserUuid(USER_UUID);

        when(memberRepository.findByUserUuid(USER_UUID)).thenReturn(List.of(member));
        when(channelRepository.findAllById(List.of(1L))).thenReturn(List.of(channel));

        var result = chatService.getUserChannels(USER_UUID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("general");
    }

    // --- getAllChannelsWithMembership ---

    @Test
    void getAllChannelsWithMembership_returnsDtoWithMemberCountAndOwner() {
        var member = new ChannelMember();
        member.setChannelId(1L);
        member.setUserUuid(USER_UUID);

        when(memberRepository.findByUserUuid(USER_UUID)).thenReturn(List.of(member));
        when(channelRepository.findAllById(any())).thenReturn(List.of(channel));
        when(memberRepository.countByChannelId(1L)).thenReturn(3L);

        var result = chatService.getAllChannelsWithMembership(USER_UUID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMemberCount()).isEqualTo(3);
        assertThat(result.get(0).isOwner()).isTrue();
    }

    @Test
    void getAllChannelsWithMembership_nonOwnerHasIsOwnerFalse() {
        channel.setCreatedByUuid(OTHER_UUID);
        var member = new ChannelMember();
        member.setChannelId(1L);
        member.setUserUuid(USER_UUID);

        when(memberRepository.findByUserUuid(USER_UUID)).thenReturn(List.of(member));
        when(channelRepository.findAllById(any())).thenReturn(List.of(channel));
        when(memberRepository.countByChannelId(1L)).thenReturn(2L);

        var result = chatService.getAllChannelsWithMembership(USER_UUID);

        assertThat(result.get(0).isOwner()).isFalse();
    }

    // --- inviteToChannel ---

    @Test
    void inviteToChannel_addsNewMember() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));
        when(memberRepository.existsByChannelIdAndUserUuid(1L, OTHER_UUID)).thenReturn(false);
        when(userCacheService.getEmail(OTHER_UUID)).thenReturn("newuser@example.com");
        when(memberRepository.save(any(ChannelMember.class))).thenAnswer(inv -> inv.getArgument(0));

        chatService.inviteToChannel(1L, OTHER_UUID, USER_UUID);

        var captor = ArgumentCaptor.forClass(ChannelMember.class);
        verify(memberRepository).save(captor.capture());
        assertThat(captor.getValue().getUserUuid()).isEqualTo(OTHER_UUID);
    }

    @Test
    void inviteToChannel_alreadyMember_doesNothing() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));
        when(memberRepository.existsByChannelIdAndUserUuid(1L, OTHER_UUID)).thenReturn(true);

        chatService.inviteToChannel(1L, OTHER_UUID, USER_UUID);

        verify(memberRepository, never()).save(any());
    }

    @Test
    void inviteToChannel_notCreator_throwsForbidden() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        assertThatThrownBy(() -> chatService.inviteToChannel(1L, OTHER_UUID, OTHER_UUID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- deleteChannel ---

    @Test
    void deleteChannel_deletesChannelAndMembers() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        chatService.deleteChannel(1L, USER_UUID);

        verify(memberRepository).deleteByChannelId(1L);
        verify(channelRepository).delete(channel);
    }

    @Test
    void deleteChannel_systemChannel_throwsForbidden() {
        channel.setSystem(true);
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        assertThatThrownBy(() -> chatService.deleteChannel(1L, USER_UUID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void deleteChannel_notCreator_throwsForbidden() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        assertThatThrownBy(() -> chatService.deleteChannel(1L, OTHER_UUID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- kickFromChannel ---

    @Test
    void kickFromChannel_removesTargetMember() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        chatService.kickFromChannel(1L, OTHER_UUID, USER_UUID);

        verify(memberRepository).deleteByChannelIdAndUserUuid(1L, OTHER_UUID);
        verify(keyBundleRepository).deleteByChannelIdAndUserUuid(1L, OTHER_UUID);
    }

    @Test
    void kickFromChannel_kickSelf_throwsBadRequest() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        assertThatThrownBy(() -> chatService.kickFromChannel(1L, USER_UUID, USER_UUID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- leaveChannel ---

    @Test
    void leaveChannel_removesUserMembership() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        chatService.leaveChannel(1L, OTHER_UUID);

        verify(memberRepository).deleteByChannelIdAndUserUuid(1L, OTHER_UUID);
    }

    @Test
    void leaveChannel_creatorCannotLeave_throwsForbidden() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        assertThatThrownBy(() -> chatService.leaveChannel(1L, USER_UUID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- sendMessage ---

    @Test
    void sendMessage_publishesToKafkaAndReturnsDto() {
        var request = new SendMessageRequest("Hello", null, null);

        var result = chatService.sendMessage(1L, request, USER_UUID, "alice@example.com");

        assertThat(result.getContent()).isEqualTo("Hello");
        assertThat(result.getChannelId()).isEqualTo(1L);
        assertThat(result.getUserUuid()).isEqualTo(USER_UUID);
        assertThat(result.getUsername()).isEqualTo("alice@example.com");

        verify(kafkaProducer).sendMessage(result);
    }

    // --- isMember ---

    @Test
    void isMember_delegatesToRepository() {
        when(memberRepository.existsByChannelIdAndUserUuid(1L, USER_UUID)).thenReturn(true);
        assertThat(chatService.isMember(1L, USER_UUID)).isTrue();

        when(memberRepository.existsByChannelIdAndUserUuid(1L, OTHER_UUID)).thenReturn(false);
        assertThat(chatService.isMember(1L, OTHER_UUID)).isFalse();
    }

    // --- updateLastRead ---

    @Test
    void updateLastRead_savesUpdatedMember() {
        var member = new ChannelMember();
        member.setChannelId(1L);
        member.setUserUuid(USER_UUID);

        when(memberRepository.findByChannelIdAndUserUuid(1L, USER_UUID)).thenReturn(Optional.of(member));
        when(memberRepository.save(any(ChannelMember.class))).thenAnswer(inv -> inv.getArgument(0));

        chatService.updateLastRead(1L, USER_UUID);

        verify(memberRepository).save(any(ChannelMember.class));
    }

    // --- getChannelMembers ---

    @Test
    void getChannelMembers_returnsEmailFromCache() {
        var m = new ChannelMember();
        m.setUserUuid(USER_UUID);
        m.setJoinedAt(Instant.now());

        when(memberRepository.findByChannelId(1L)).thenReturn(List.of(m));
        when(userCacheService.getEmail(USER_UUID)).thenReturn("alice@example.com");

        var result = chatService.getChannelMembers(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("email", "alice@example.com");
    }

    // --- uploadUserKeys ---

    @Test
    void uploadUserKeys_savesKeysAndReturnsDto() {
        when(userKeysRepository.existsByUserUuid(USER_UUID)).thenReturn(false);
        when(userKeysRepository.save(any(UserKeys.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new UploadKeysRequest("pubkey", "encprivkey", "salt123456789012345678", 600000);
        var result = chatService.uploadUserKeys(request, USER_UUID);

        assertThat(result.getPublicKey()).isEqualTo("pubkey");
    }

    @Test
    void uploadUserKeys_alreadyExist_throwsConflict() {
        when(userKeysRepository.existsByUserUuid(USER_UUID)).thenReturn(true);

        var request = new UploadKeysRequest("pubkey", "encprivkey", "salt123456789012345678", 600000);
        assertThatThrownBy(() -> chatService.uploadUserKeys(request, USER_UUID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // --- updateUserKeys ---

    @Test
    void updateUserKeys_incrementsVersionAndSaves() {
        var existingKeys = new UserKeys();
        existingKeys.setUserUuid(USER_UUID);
        existingKeys.setPublicKey("oldpub");
        existingKeys.setEncryptedPrivateKey("oldpriv");
        existingKeys.setPbkdf2Salt("oldsalt123456789012345");
        existingKeys.setPbkdf2Iterations(600000);
        existingKeys.setKeyVersion(1);

        when(userKeysRepository.findByUserUuid(USER_UUID)).thenReturn(Optional.of(existingKeys));
        when(userKeysRepository.save(any(UserKeys.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new UploadKeysRequest("newpub", "newpriv", "newsalt12345678901234", 600000);
        chatService.updateUserKeys(request, USER_UUID);

        assertThat(existingKeys.getKeyVersion()).isEqualTo(2);
        assertThat(existingKeys.getPublicKey()).isEqualTo("newpub");
    }

    // --- setChannelKeys ---

    @Test
    void setChannelKeys_initialVersion_setsVersionTo1() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));
        when(memberRepository.existsByChannelIdAndUserUuid(1L, USER_UUID)).thenReturn(true);
        when(keyBundleRepository.findByChannelIdAndUserUuidAndKeyVersion(1L, USER_UUID, 1)).thenReturn(Optional.empty());
        when(keyBundleRepository.save(any(ChannelKeyBundle.class))).thenAnswer(inv -> inv.getArgument(0));
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        var bundle = new SetChannelKeysRequest.MemberKeyBundle(USER_UUID, "enckey", "wrappub");
        var request = new SetChannelKeysRequest(List.of(bundle));

        chatService.setChannelKeys(1L, request, USER_UUID);

        assertThat(channel.getCurrentKeyVersion()).isEqualTo(1);
        verify(keyBundleRepository).save(any(ChannelKeyBundle.class));
    }

    @Test
    void setChannelKeys_notCreator_throwsForbidden() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        var bundle = new SetChannelKeysRequest.MemberKeyBundle(USER_UUID, "enckey", "wrappub");
        var request = new SetChannelKeysRequest(List.of(bundle));

        assertThatThrownBy(() -> chatService.setChannelKeys(1L, request, OTHER_UUID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- rotateChannelKeys ---

    @Test
    void rotateChannelKeys_incrementsVersionAndSavesBundles() {
        channel.setCurrentKeyVersion(1);
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));
        when(memberRepository.existsByChannelIdAndUserUuid(1L, USER_UUID)).thenReturn(true);
        when(keyBundleRepository.save(any(ChannelKeyBundle.class))).thenAnswer(inv -> inv.getArgument(0));
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        var bundle = new SetChannelKeysRequest.MemberKeyBundle(USER_UUID, "enckey2", "wrappub2");
        var request = new SetChannelKeysRequest(List.of(bundle));

        int newVersion = chatService.rotateChannelKeys(1L, request, USER_UUID);

        assertThat(newVersion).isEqualTo(2);
    }

    // --- editMessage ---

    @Test
    void editMessage_ownMessage_savesEdit() {
        String msgId = UUID.randomUUID().toString();
        var original = ChatMessageDto.builder()
                .messageId(msgId).channelId(1L).userUuid(USER_UUID)
                .username("alice").content("Original").createdAt(Instant.now()).hash("original-hash").build();

        when(messageRepository.getRecentMessages(1L, 100)).thenReturn(List.of(original));

        chatService.editMessage(1L, msgId, "Edited content", USER_UUID);

        verify(messageRepository).saveEdit(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.eq(USER_UUID),
                org.mockito.ArgumentMatchers.eq("Edited content"),
                org.mockito.ArgumentMatchers.eq("original-hash")
        );
    }

    @Test
    void editMessage_otherUsersMessage_throwsForbidden() {
        String msgId = UUID.randomUUID().toString();
        var original = ChatMessageDto.builder()
                .messageId(msgId).channelId(1L).userUuid(OTHER_UUID)
                .username("bob").content("Bob's message").createdAt(Instant.now()).hash("hash").build();

        when(messageRepository.getRecentMessages(1L, 100)).thenReturn(List.of(original));

        assertThatThrownBy(() -> chatService.editMessage(1L, msgId, "Hijacked", USER_UUID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- getPublicKeys ---

    @Test
    void getPublicKeys_returnsMappedResults() {
        var keys = new UserKeys();
        keys.setUserUuid(USER_UUID);
        keys.setPublicKey("pubkey10");
        keys.setKeyVersion(1);

        when(userKeysRepository.findByUserUuidIn(List.of(USER_UUID, OTHER_UUID))).thenReturn(List.of(keys));

        var result = chatService.getPublicKeys(List.of(USER_UUID, OTHER_UUID));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("userUuid", USER_UUID.toString());
        assertThat(result.get(0)).containsEntry("publicKey", "pubkey10");
    }

    // --- getChannelKeyBundle ---

    @Test
    void getChannelKeyBundle_bundleExists_returnsDto() {
        channel.setCurrentKeyVersion(1);
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        var bundle = new ChannelKeyBundle();
        bundle.setChannelId(1L);
        bundle.setUserUuid(USER_UUID);
        bundle.setKeyVersion(1);
        bundle.setEncryptedChannelKey("enckey");
        bundle.setWrapperPublicKey("wrappub");

        when(keyBundleRepository.findByChannelIdAndUserUuidAndKeyVersion(1L, USER_UUID, 1))
                .thenReturn(Optional.of(bundle));

        var result = chatService.getChannelKeyBundle(1L, USER_UUID);

        assertThat(result).isNotNull();
        assertThat(result.getEncryptedChannelKey()).isEqualTo("enckey");
    }

    // --- verifyChain ---

    @Test
    void verifyChain_delegatesToRepository() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        chatService.verifyChain(1L);

        verify(messageRepository).verifyChain(1L, channel.getCreatedAt());
    }

    // --- deleteChannel (verifies all cleanup) ---

    @Test
    void deleteChannel_deletesMessagesAndKeysAndMembers() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        chatService.deleteChannel(1L, USER_UUID);

        verify(messageRepository).deleteMessagesByChannel(1L, channel.getCreatedAt());
        verify(keyBundleRepository).deleteByChannelId(1L);
        verify(memberRepository).deleteByChannelId(1L);
        verify(channelRepository).delete(channel);
    }

    // --- getMessageEdits ---

    @Test
    void getMessageEdits_delegatesToRepositoryWithCorrectBucket() {
        UUID msgUuid = com.datastax.oss.driver.api.core.uuid.Uuids.timeBased();
        String msgId = msgUuid.toString();

        var edit = new ScyllaMessageRepository.EditRecord("edit-1", USER_UUID, "edited", "hash", Instant.now());
        Instant msgTime = Instant.ofEpochMilli(com.datastax.oss.driver.api.core.uuid.Uuids.unixTimestamp(msgUuid));
        String expectedBucket = ScyllaMessageRepository.bucketForDate(msgTime);

        when(messageRepository.getEdits(1L, expectedBucket, msgUuid)).thenReturn(List.of(edit));

        var result = chatService.getMessageEdits(1L, msgId);

        assertThat(result).hasSize(1);
        verify(messageRepository).getEdits(1L, expectedBucket, msgUuid);
    }

    // --- not found cases ---

    @Test
    void deleteChannel_notFound_throwsIllegalArgument() {
        when(channelRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> chatService.deleteChannel(1L, USER_UUID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void kickFromChannel_channelNotFound_throwsIllegalArgument() {
        when(channelRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> chatService.kickFromChannel(1L, OTHER_UUID, USER_UUID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leaveChannel_channelNotFound_throwsIllegalArgument() {
        when(channelRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> chatService.leaveChannel(1L, OTHER_UUID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyChain_channelNotFound_throwsIllegalArgument() {
        when(channelRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> chatService.verifyChain(1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
