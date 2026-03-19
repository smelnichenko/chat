package io.schnappy.chat.service;

import io.schnappy.chat.dto.CreateChannelRequest;
import io.schnappy.chat.dto.SendMessageRequest;
import io.schnappy.chat.dto.SetChannelKeysRequest;
import io.schnappy.chat.dto.UploadKeysRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private ChannelMemberRepository memberRepository;
    @Mock
    private ScyllaMessageRepository messageRepository;
    @Mock
    private ChatKafkaProducer kafkaProducer;
    @Mock
    private UserCacheService userCacheService;
    @Mock
    private UserKeysRepository userKeysRepository;
    @Mock
    private ChannelKeyBundleRepository keyBundleRepository;

    @InjectMocks
    private ChatService chatService;

    private Channel channel;

    @BeforeEach
    void setUp() {
        channel = new Channel();
        channel.setId(1L);
        channel.setName("general");
        channel.setCreatedBy(10L);
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

        var result = chatService.createChannel(request, 10L);

        assertThat(result.getName()).isEqualTo("general");
        assertThat(result.getCreatedBy()).isEqualTo(10L);
        assertThat(result.isEncrypted()).isFalse();

        var memberCaptor = ArgumentCaptor.forClass(ChannelMember.class);
        verify(memberRepository).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getUserId()).isEqualTo(10L);
        assertThat(memberCaptor.getValue().getChannelId()).isEqualTo(1L);
    }

    @Test
    void createChannel_withEncryptedTrue_setsEncrypted() {
        var request = new CreateChannelRequest("secret", true);
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> {
            Channel c = inv.getArgument(0);
            c.setId(2L);
            return c;
        });
        when(memberRepository.save(any(ChannelMember.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = chatService.createChannel(request, 10L);

        assertThat(result.isEncrypted()).isTrue();
    }

    @Test
    void createChannel_withNullEncrypted_defaultsFalse() {
        var request = new CreateChannelRequest("open", null);
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> {
            Channel c = inv.getArgument(0);
            c.setId(3L);
            return c;
        });
        when(memberRepository.save(any(ChannelMember.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = chatService.createChannel(request, 10L);

        assertThat(result.isEncrypted()).isFalse();
    }

    // --- getUserChannels ---

    @Test
    void getUserChannels_returnsChannelsForMemberships() {
        var member = new ChannelMember();
        member.setChannelId(1L);
        member.setUserId(10L);

        when(memberRepository.findByUserId(10L)).thenReturn(List.of(member));
        when(channelRepository.findAllById(List.of(1L))).thenReturn(List.of(channel));

        var result = chatService.getUserChannels(10L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("general");
    }

    // --- getAllChannelsWithMembership ---

    @Test
    void getAllChannelsWithMembership_returnsDtoWithMemberCountAndOwner() {
        var member = new ChannelMember();
        member.setChannelId(1L);
        member.setUserId(10L);

        when(memberRepository.findByUserId(10L)).thenReturn(List.of(member));
        when(channelRepository.findAllById(any())).thenReturn(List.of(channel));
        when(memberRepository.countByChannelId(1L)).thenReturn(3L);

        var result = chatService.getAllChannelsWithMembership(10L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMemberCount()).isEqualTo(3);
        assertThat(result.get(0).isJoined()).isTrue();
        assertThat(result.get(0).isOwner()).isTrue();
        assertThat(result.get(0).isSystem()).isFalse();
    }

    @Test
    void getAllChannelsWithMembership_nonOwnerHasIsOwnerFalse() {
        channel.setCreatedBy(99L); // different user
        var member = new ChannelMember();
        member.setChannelId(1L);
        member.setUserId(10L);

        when(memberRepository.findByUserId(10L)).thenReturn(List.of(member));
        when(channelRepository.findAllById(any())).thenReturn(List.of(channel));
        when(memberRepository.countByChannelId(1L)).thenReturn(2L);

        var result = chatService.getAllChannelsWithMembership(10L);

        assertThat(result.get(0).isOwner()).isFalse();
    }

    // --- inviteToChannel ---

    @Test
    void inviteToChannel_addsNewMember() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));
        when(memberRepository.existsByChannelIdAndUserId(1L, 20L)).thenReturn(false);
        when(userCacheService.getEmail(20L)).thenReturn("newuser@example.com");
        when(memberRepository.save(any(ChannelMember.class))).thenAnswer(inv -> inv.getArgument(0));

        chatService.inviteToChannel(1L, 20L, 10L);

        var captor = ArgumentCaptor.forClass(ChannelMember.class);
        verify(memberRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(20L);
    }

    @Test
    void inviteToChannel_alreadyMember_doesNothing() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));
        when(memberRepository.existsByChannelIdAndUserId(1L, 20L)).thenReturn(true);

        chatService.inviteToChannel(1L, 20L, 10L);

        verify(memberRepository, never()).save(any());
    }

    @Test
    void inviteToChannel_notCreator_throwsForbidden() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        assertThatThrownBy(() -> chatService.inviteToChannel(1L, 20L, 99L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void inviteToChannel_userNotInCache_throwsIllegalArgument() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));
        when(memberRepository.existsByChannelIdAndUserId(1L, 20L)).thenReturn(false);
        when(userCacheService.getEmail(20L)).thenReturn(null);

        assertThatThrownBy(() -> chatService.inviteToChannel(1L, 20L, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void inviteToChannel_channelNotFound_throwsIllegalArgument() {
        when(channelRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.inviteToChannel(1L, 20L, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Channel not found");
    }

    // --- deleteChannel ---

    @Test
    void deleteChannel_deletesChannelAndMembers() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        chatService.deleteChannel(1L, 10L);

        verify(memberRepository).deleteByChannelId(1L);
        verify(channelRepository).delete(channel);
    }

    @Test
    void deleteChannel_systemChannel_throwsForbidden() {
        channel.setSystem(true);
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        assertThatThrownBy(() -> chatService.deleteChannel(1L, 10L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void deleteChannel_notCreator_throwsForbidden() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        assertThatThrownBy(() -> chatService.deleteChannel(1L, 99L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- kickFromChannel ---

    @Test
    void kickFromChannel_removesTargetMember() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        chatService.kickFromChannel(1L, 20L, 10L);

        verify(memberRepository).deleteByChannelIdAndUserId(1L, 20L);
        verify(keyBundleRepository).deleteByChannelIdAndUserId(1L, 20L);
    }

    @Test
    void kickFromChannel_kickSelf_throwsBadRequest() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        assertThatThrownBy(() -> chatService.kickFromChannel(1L, 10L, 10L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void kickFromChannel_notCreator_throwsForbidden() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        assertThatThrownBy(() -> chatService.kickFromChannel(1L, 20L, 99L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void kickFromChannel_systemChannel_throwsForbidden() {
        channel.setSystem(true);
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        assertThatThrownBy(() -> chatService.kickFromChannel(1L, 20L, 10L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- leaveChannel ---

    @Test
    void leaveChannel_removesUserMembership() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        chatService.leaveChannel(1L, 20L);

        verify(memberRepository).deleteByChannelIdAndUserId(1L, 20L);
        verify(keyBundleRepository).deleteByChannelIdAndUserId(1L, 20L);
    }

    @Test
    void leaveChannel_systemChannel_throwsForbidden() {
        channel.setSystem(true);
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        assertThatThrownBy(() -> chatService.leaveChannel(1L, 20L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void leaveChannel_creatorCannotLeave_throwsForbidden() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        assertThatThrownBy(() -> chatService.leaveChannel(1L, 10L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- sendMessage ---

    @Test
    void sendMessage_publishesToKafkaAndReturnsDto() {
        var request = new SendMessageRequest("Hello", null, null);

        var result = chatService.sendMessage(1L, request, 10L, "alice@example.com");

        assertThat(result.getContent()).isEqualTo("Hello");
        assertThat(result.getChannelId()).isEqualTo(1L);
        assertThat(result.getUserId()).isEqualTo(10L);
        assertThat(result.getUsername()).isEqualTo("alice@example.com");
        assertThat(result.getMessageId()).isNotNull();
        assertThat(result.getCreatedAt()).isNotNull();

        verify(kafkaProducer).sendMessage(result);
    }

    @Test
    void sendMessage_withParentAndKeyVersion_preservesFields() {
        var request = new SendMessageRequest("Reply", "parent-uuid", 2);

        var result = chatService.sendMessage(1L, request, 10L, "alice");

        assertThat(result.getParentMessageId()).isEqualTo("parent-uuid");
        assertThat(result.getKeyVersion()).isEqualTo(2);
    }

    // --- isMember ---

    @Test
    void isMember_delegatesToRepository() {
        when(memberRepository.existsByChannelIdAndUserId(1L, 10L)).thenReturn(true);
        assertThat(chatService.isMember(1L, 10L)).isTrue();

        when(memberRepository.existsByChannelIdAndUserId(1L, 99L)).thenReturn(false);
        assertThat(chatService.isMember(1L, 99L)).isFalse();
    }

    // --- updateLastRead ---

    @Test
    void updateLastRead_savesUpdatedMember() {
        var member = new ChannelMember();
        member.setChannelId(1L);
        member.setUserId(10L);

        when(memberRepository.findByChannelIdAndUserId(1L, 10L)).thenReturn(Optional.of(member));
        when(memberRepository.save(any(ChannelMember.class))).thenAnswer(inv -> inv.getArgument(0));

        chatService.updateLastRead(1L, 10L);

        var captor = ArgumentCaptor.forClass(ChannelMember.class);
        verify(memberRepository).save(captor.capture());
        assertThat(captor.getValue().getLastReadAt()).isNotNull();
    }

    @Test
    void updateLastRead_memberNotFound_doesNothing() {
        when(memberRepository.findByChannelIdAndUserId(1L, 10L)).thenReturn(Optional.empty());

        chatService.updateLastRead(1L, 10L);

        verify(memberRepository, never()).save(any());
    }

    // --- getChannelMembers ---

    @Test
    void getChannelMembers_returnsEmailFromCache() {
        var m = new ChannelMember();
        m.setUserId(10L);
        m.setJoinedAt(Instant.now());

        when(memberRepository.findByChannelId(1L)).thenReturn(List.of(m));
        when(userCacheService.getEmail(10L)).thenReturn("alice@example.com");

        var result = chatService.getChannelMembers(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("email")).isEqualTo("alice@example.com");
    }

    @Test
    void getChannelMembers_unknownUser_returnsUnknown() {
        var m = new ChannelMember();
        m.setUserId(99L);
        m.setJoinedAt(Instant.now());

        when(memberRepository.findByChannelId(1L)).thenReturn(List.of(m));
        when(userCacheService.getEmail(99L)).thenReturn(null);

        var result = chatService.getChannelMembers(1L);

        assertThat(result.get(0).get("email")).isEqualTo("unknown");
    }

    // --- uploadUserKeys ---

    @Test
    void uploadUserKeys_savesKeysAndReturnsDto() {
        when(userKeysRepository.existsByUserId(10L)).thenReturn(false);
        when(userKeysRepository.save(any(UserKeys.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new UploadKeysRequest("pubkey", "encprivkey", "salt123456789012345678", 600000);
        var result = chatService.uploadUserKeys(request, 10L);

        assertThat(result.getPublicKey()).isEqualTo("pubkey");
        assertThat(result.getEncryptedPrivateKey()).isEqualTo("encprivkey");
    }

    @Test
    void uploadUserKeys_alreadyExist_throwsConflict() {
        when(userKeysRepository.existsByUserId(10L)).thenReturn(true);

        var request = new UploadKeysRequest("pubkey", "encprivkey", "salt123456789012345678", 600000);
        assertThatThrownBy(() -> chatService.uploadUserKeys(request, 10L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // --- updateUserKeys ---

    @Test
    void updateUserKeys_incrementsVersionAndSaves() {
        var existingKeys = new UserKeys();
        existingKeys.setUserId(10L);
        existingKeys.setPublicKey("oldpub");
        existingKeys.setEncryptedPrivateKey("oldpriv");
        existingKeys.setPbkdf2Salt("oldsalt123456789012345");
        existingKeys.setPbkdf2Iterations(600000);
        existingKeys.setKeyVersion(1);

        when(userKeysRepository.findByUserId(10L)).thenReturn(Optional.of(existingKeys));
        when(userKeysRepository.save(any(UserKeys.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new UploadKeysRequest("newpub", "newpriv", "newsalt12345678901234", 600000);
        chatService.updateUserKeys(request, 10L);

        assertThat(existingKeys.getKeyVersion()).isEqualTo(2);
        assertThat(existingKeys.getPublicKey()).isEqualTo("newpub");
    }

    @Test
    void updateUserKeys_keysNotFound_throwsNotFound() {
        when(userKeysRepository.findByUserId(10L)).thenReturn(Optional.empty());

        var request = new UploadKeysRequest("newpub", "newpriv", "newsalt12345678901234", 600000);
        assertThatThrownBy(() -> chatService.updateUserKeys(request, 10L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- setChannelKeys ---

    @Test
    void setChannelKeys_initialVersion_setsVersionTo1() {
        // channel at version 0 -> should go to 1
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));
        when(memberRepository.existsByChannelIdAndUserId(1L, 10L)).thenReturn(true);
        when(keyBundleRepository.findByChannelIdAndUserIdAndKeyVersion(1L, 10L, 1)).thenReturn(Optional.empty());
        when(keyBundleRepository.save(any(ChannelKeyBundle.class))).thenAnswer(inv -> inv.getArgument(0));
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        var bundle = new SetChannelKeysRequest.MemberKeyBundle(10L, "enckey", "wrappub");
        var request = new SetChannelKeysRequest(List.of(bundle));

        chatService.setChannelKeys(1L, request, 10L);

        assertThat(channel.getCurrentKeyVersion()).isEqualTo(1);
        verify(keyBundleRepository).save(any(ChannelKeyBundle.class));
        verify(channelRepository).save(channel);
    }

    @Test
    void setChannelKeys_notCreator_throwsForbidden() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        var bundle = new SetChannelKeysRequest.MemberKeyBundle(10L, "enckey", "wrappub");
        var request = new SetChannelKeysRequest(List.of(bundle));

        assertThatThrownBy(() -> chatService.setChannelKeys(1L, request, 99L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- rotateChannelKeys ---

    @Test
    void rotateChannelKeys_incrementsVersionAndSavesBundles() {
        channel.setCurrentKeyVersion(1);
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));
        when(memberRepository.existsByChannelIdAndUserId(1L, 10L)).thenReturn(true);
        when(keyBundleRepository.save(any(ChannelKeyBundle.class))).thenAnswer(inv -> inv.getArgument(0));
        when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> inv.getArgument(0));

        var bundle = new SetChannelKeysRequest.MemberKeyBundle(10L, "enckey2", "wrappub2");
        var request = new SetChannelKeysRequest(List.of(bundle));

        int newVersion = chatService.rotateChannelKeys(1L, request, 10L);

        assertThat(newVersion).isEqualTo(2);
        assertThat(channel.getCurrentKeyVersion()).isEqualTo(2);
        verify(keyBundleRepository).save(any(ChannelKeyBundle.class));
    }

    @Test
    void rotateChannelKeys_notCreator_throwsForbidden() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(channel));

        var bundle = new SetChannelKeysRequest.MemberKeyBundle(10L, "enckey2", "wrappub2");
        var request = new SetChannelKeysRequest(List.of(bundle));

        assertThatThrownBy(() -> chatService.rotateChannelKeys(1L, request, 99L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- getChannelKeyBundles ---

    @Test
    void getChannelKeyBundles_notMember_throwsForbidden() {
        when(memberRepository.existsByChannelIdAndUserId(1L, 99L)).thenReturn(false);

        assertThatThrownBy(() -> chatService.getChannelKeyBundles(1L, 99L, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getChannelKeyBundles_memberWithoutFilter_returnsAll() {
        when(memberRepository.existsByChannelIdAndUserId(1L, 10L)).thenReturn(true);

        var bundle = new ChannelKeyBundle();
        bundle.setChannelId(1L);
        bundle.setUserId(10L);
        bundle.setKeyVersion(1);
        bundle.setEncryptedChannelKey("enckey");
        bundle.setWrapperPublicKey("wrappub");

        when(keyBundleRepository.findByChannelIdAndUserId(1L, 10L)).thenReturn(List.of(bundle));

        var result = chatService.getChannelKeyBundles(1L, 10L, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEncryptedChannelKey()).isEqualTo("enckey");
    }
}
