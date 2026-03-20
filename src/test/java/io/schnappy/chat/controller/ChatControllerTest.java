package io.schnappy.chat.controller;

import io.schnappy.chat.config.ChatProperties;
import io.schnappy.chat.dto.ChannelDto;
import io.schnappy.chat.dto.ChannelKeyBundleDto;
import io.schnappy.chat.dto.ChatMessageDto;
import io.schnappy.chat.dto.CreateChannelRequest;
import io.schnappy.chat.dto.EditMessageRequest;
import io.schnappy.chat.dto.SendMessageRequest;
import io.schnappy.chat.dto.SetChannelKeysRequest;
import io.schnappy.chat.dto.UploadKeysRequest;
import io.schnappy.chat.dto.UserKeysDto;
import io.schnappy.chat.entity.Channel;
import io.schnappy.chat.repository.ScyllaMessageRepository;
import io.schnappy.chat.security.GatewayUser;
import io.schnappy.chat.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ChatService chatService;

    @Mock
    private ChatProperties chatProperties;

    @InjectMocks
    private ChatController chatController;

    private GatewayUser user;
    private Channel channel;

    @BeforeEach
    void setUp() {
        user = new GatewayUser(10L, "uuid-abc", "alice@example.com", List.of("CHAT"));
        channel = new Channel();
        channel.setId(1L);
        channel.setName("general");
        channel.setCreatedBy(10L);
    }

    // --- getChannels ---

    @Test
    void getChannels_returnsChannelDtos() {
        var dto = ChannelDto.builder().id(1L).name("general").memberCount(1).joined(true).isOwner(true).build();
        when(chatService.getAllChannelsWithMembership(10L)).thenReturn(List.of(dto));

        var result = chatController.getChannels(user);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("general");
    }

    // --- createChannel ---

    @Test
    void createChannel_returnsCreatedWithChannelDto() {
        channel.setCreatedBy(10L);
        when(chatService.createChannel(any(CreateChannelRequest.class), eq(10L))).thenReturn(channel);

        var request = new CreateChannelRequest("general", false);
        var response = chatController.createChannel(request, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getName()).isEqualTo("general");
        assertThat(response.getBody().isOwner()).isTrue();
        assertThat(response.getBody().getMemberCount()).isEqualTo(1);
    }

    // --- leaveChannel ---

    @Test
    void leaveChannel_returns200() {
        var response = chatController.leaveChannel(1L, user);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(chatService).leaveChannel(1L, 10L);
    }

    // --- deleteChannel ---

    @Test
    void deleteChannel_returns204() {
        var response = chatController.deleteChannel(1L, user);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(chatService).deleteChannel(1L, 10L);
    }

    // --- getMessages ---

    @Test
    void getMessages_memberCanFetch() {
        when(chatService.isMember(1L, 10L)).thenReturn(true);
        var msg = new ChatMessageDto("id1", 1L, 10L, "alice", "Hello", null, Instant.now(), null, null, null, null, null, null);
        when(chatService.getMessages(1L, 50)).thenReturn(List.of(msg));

        var result = chatController.getMessages(1L, 50, user);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("Hello");
    }

    @Test
    void getMessages_limitCappedAt100() {
        when(chatService.isMember(1L, 10L)).thenReturn(true);
        when(chatService.getMessages(1L, 100)).thenReturn(List.of());

        chatController.getMessages(1L, 999, user);

        verify(chatService).getMessages(1L, 100);
    }

    @Test
    void getMessages_notMember_throwsForbidden() {
        when(chatService.isMember(1L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> chatController.getMessages(1L, 50, user))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- sendMessage ---

    @Test
    void sendMessage_memberCanSend_returnsCreated() {
        when(chatService.isMember(1L, 10L)).thenReturn(true);
        var msg = ChatMessageDto.builder()
                .messageId("uuid-1")
                .channelId(1L)
                .userId(10L)
                .content("Hello")
                .createdAt(Instant.now())
                .build();
        when(chatService.sendMessage(eq(1L), any(SendMessageRequest.class), eq(10L), eq("alice@example.com")))
                .thenReturn(msg);

        var request = new SendMessageRequest("Hello", null, null);
        var response = chatController.sendMessage(1L, request, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).isEqualTo("Hello");
    }

    @Test
    void sendMessage_notMember_throwsForbidden() {
        when(chatService.isMember(1L, 10L)).thenReturn(false);

        var request = new SendMessageRequest("Hello", null, null);
        assertThatThrownBy(() -> chatController.sendMessage(1L, request, user))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- getMembers ---

    @Test
    void getMembers_memberCanView() {
        when(chatService.isMember(1L, 10L)).thenReturn(true);
        when(chatService.getChannelMembers(1L))
                .thenReturn(List.of(Map.of("id", 10L, "email", "alice@example.com", "joinedAt", "2025-01-01T00:00:00Z")));

        var result = chatController.getMembers(1L, user);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("email", "alice@example.com");
    }

    @Test
    void getMembers_notMember_throwsForbidden() {
        when(chatService.isMember(1L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> chatController.getMembers(1L, user))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- kickUser ---

    @Test
    void kickUser_validBody_delegates() {
        var response = chatController.kickUser(1L, Map.of("userId", 20L), user);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(chatService).kickFromChannel(1L, 20L, 10L);
    }

    @Test
    void kickUser_missingUserId_throwsBadRequest() {
        var thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                ResponseStatusException.class, () -> chatController.kickUser(1L, Map.of(), user));
        assertThat(thrown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- inviteUser ---

    @Test
    void inviteUser_validBody_delegates() {
        var response = chatController.inviteUser(1L, Map.of("userId", 20L), user);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(chatService).inviteToChannel(1L, 20L, 10L);
    }

    @Test
    void inviteUser_missingUserId_throwsBadRequest() {
        var thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                ResponseStatusException.class, () -> chatController.inviteUser(1L, Map.of(), user));
        assertThat(thrown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- markAsRead ---

    @Test
    void markAsRead_delegates() {
        var response = chatController.markAsRead(1L, user);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(chatService).updateLastRead(1L, 10L);
    }

    // --- E2E disabled ---

    @Test
    void getKeys_e2eDisabled_throwsNotFound() {
        when(chatProperties.e2eEnabled()).thenReturn(false);

        assertThatThrownBy(() -> chatController.getKeys(user))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getPublicKeys_e2eDisabled_throwsNotFound() {
        when(chatProperties.e2eEnabled()).thenReturn(false);

        var thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                ResponseStatusException.class, () -> chatController.getPublicKeys(List.of(1L, 2L)));
        assertThat(thrown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- getKeys (e2e enabled) ---

    @Test
    void getKeys_e2eEnabled_keysFound_returnsOk() {
        when(chatProperties.e2eEnabled()).thenReturn(true);
        var keysDto = io.schnappy.chat.dto.UserKeysDto.builder()
                .publicKey("pubkey").encryptedPrivateKey("encpriv").pbkdf2Salt("salt").pbkdf2Iterations(600000).keyVersion(1).build();
        when(chatService.getUserKeys(10L)).thenReturn(keysDto);

        var response = chatController.getKeys(user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getPublicKey()).isEqualTo("pubkey");
    }

    @Test
    void getKeys_e2eEnabled_keysNotFound_returnsNotFound() {
        when(chatProperties.e2eEnabled()).thenReturn(true);
        when(chatService.getUserKeys(10L)).thenReturn(null);

        var response = chatController.getKeys(user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- rotateChannelKeys ---

    @Test
    void rotateChannelKeys_e2eEnabled_returnsNewVersion() {
        when(chatProperties.e2eEnabled()).thenReturn(true);
        when(chatService.rotateChannelKeys(eq(1L), any(), eq(10L))).thenReturn(3);

        var bundle = new SetChannelKeysRequest.MemberKeyBundle(10L, "enckey", "wrappub");
        var request = new SetChannelKeysRequest(List.of(bundle));
        var response = chatController.rotateChannelKeys(1L, request, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("newKeyVersion", 3);
    }

    // --- getUsers ---

    @Test
    void getUsers_delegatesToService() {
        when(chatService.getChatUsers(10L))
                .thenReturn(List.of(Map.of("id", 20L, "email", "bob@example.com")));

        var result = chatController.getUsers(user);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("email", "bob@example.com");
    }

    // --- editMessage ---

    @Test
    void editMessage_memberCanEdit_returnsOk() {
        when(chatService.isMember(1L, 10L)).thenReturn(true);

        var request = new EditMessageRequest("Updated content");
        var response = chatController.editMessage(1L, "msg-uuid", request, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(chatService).editMessage(1L, "msg-uuid", "Updated content", 10L);
    }

    @Test
    void editMessage_notMember_throwsForbidden() {
        when(chatService.isMember(1L, 10L)).thenReturn(false);

        var request = new EditMessageRequest("Updated");
        assertThatThrownBy(() -> chatController.editMessage(1L, "msg-uuid", request, user))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- getMessageEdits ---

    @Test
    void getMessageEdits_memberCanView() {
        when(chatService.isMember(1L, 10L)).thenReturn(true);
        var edit = new ScyllaMessageRepository.EditRecord("edit-1", 10L, "v2", "hash", java.time.Instant.now());
        when(chatService.getMessageEdits(1L, "msg-uuid")).thenReturn(List.of(edit));

        var result = chatController.getMessageEdits(1L, "msg-uuid", user);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("v2");
    }

    @Test
    void getMessageEdits_notMember_throwsForbidden() {
        when(chatService.isMember(1L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> chatController.getMessageEdits(1L, "msg-uuid", user))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- verifyChain ---

    @Test
    void verifyChain_memberCanVerify() {
        when(chatService.isMember(1L, 10L)).thenReturn(true);
        var verification = new ScyllaMessageRepository.ChainVerification(5, 5, true, null);
        when(chatService.verifyChain(1L)).thenReturn(verification);

        var result = chatController.verifyChain(1L, user);

        assertThat(result.intact()).isTrue();
        assertThat(result.messageCount()).isEqualTo(5);
    }

    @Test
    void verifyChain_notMember_throwsForbidden() {
        when(chatService.isMember(1L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> chatController.verifyChain(1L, user))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- uploadKeys (e2e enabled) ---

    @Test
    void uploadKeys_e2eEnabled_returnsCreated() {
        when(chatProperties.e2eEnabled()).thenReturn(true);
        var keysDto = UserKeysDto.builder()
                .publicKey("pubkey").encryptedPrivateKey("encpriv")
                .pbkdf2Salt("salt123456789012345678").pbkdf2Iterations(600000).keyVersion(1).build();
        when(chatService.uploadUserKeys(any(UploadKeysRequest.class), eq(10L))).thenReturn(keysDto);

        var request = new UploadKeysRequest("pubkey", "encpriv", "salt123456789012345678", 600000);
        var response = chatController.uploadKeys(request, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getPublicKey()).isEqualTo("pubkey");
    }

    @Test
    void uploadKeys_e2eDisabled_throwsNotFound() {
        when(chatProperties.e2eEnabled()).thenReturn(false);

        var request = new UploadKeysRequest("pubkey", "encpriv", "salt123456789012345678", 600000);
        assertThatThrownBy(() -> chatController.uploadKeys(request, user))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- updateKeys (e2e enabled) ---

    @Test
    void updateKeys_e2eEnabled_returnsOk() {
        when(chatProperties.e2eEnabled()).thenReturn(true);

        var request = new UploadKeysRequest("newpub", "newpriv", "newsalt12345678901234567", 600000);
        var response = chatController.updateKeys(request, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(chatService).updateUserKeys(any(UploadKeysRequest.class), eq(10L));
    }

    @Test
    void updateKeys_e2eDisabled_throwsNotFound() {
        when(chatProperties.e2eEnabled()).thenReturn(false);

        var request = new UploadKeysRequest("newpub", "newpriv", "newsalt12345678901234567", 600000);
        assertThatThrownBy(() -> chatController.updateKeys(request, user))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- getPublicKeys (e2e enabled) ---

    @Test
    void getPublicKeys_e2eEnabled_returnsResults() {
        when(chatProperties.e2eEnabled()).thenReturn(true);
        when(chatService.getPublicKeys(List.of(10L, 20L)))
                .thenReturn(List.of(Map.of("userId", 10L, "publicKey", "pk10", "keyVersion", 1)));

        var result = chatController.getPublicKeys(List.of(10L, 20L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("publicKey", "pk10");
    }

    // --- getChannelKeys (e2e enabled) ---

    @Test
    void getChannelKeys_e2eEnabled_memberCanFetch() {
        when(chatProperties.e2eEnabled()).thenReturn(true);
        when(chatService.isMember(1L, 10L)).thenReturn(true);
        var bundleDto = ChannelKeyBundleDto.builder()
                .userId(10L).keyVersion(1).encryptedChannelKey("enckey").wrapperPublicKey("wrappub").build();
        when(chatService.getChannelKeyBundles(1L, 10L, null)).thenReturn(List.of(bundleDto));

        var response = chatController.getChannelKeys(1L, null, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getEncryptedChannelKey()).isEqualTo("enckey");
    }

    @Test
    void getChannelKeys_e2eEnabled_withKeyVersion() {
        when(chatProperties.e2eEnabled()).thenReturn(true);
        when(chatService.isMember(1L, 10L)).thenReturn(true);
        when(chatService.getChannelKeyBundles(1L, 10L, 2)).thenReturn(List.of());

        var response = chatController.getChannelKeys(1L, 2, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(chatService).getChannelKeyBundles(1L, 10L, 2);
    }

    @Test
    void getChannelKeys_notMember_throwsForbidden() {
        when(chatProperties.e2eEnabled()).thenReturn(true);
        when(chatService.isMember(1L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> chatController.getChannelKeys(1L, null, user))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getChannelKeys_e2eDisabled_throwsNotFound() {
        when(chatProperties.e2eEnabled()).thenReturn(false);

        assertThatThrownBy(() -> chatController.getChannelKeys(1L, null, user))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- setChannelKeys (e2e enabled) ---

    @Test
    void setChannelKeys_e2eEnabled_returnsOk() {
        when(chatProperties.e2eEnabled()).thenReturn(true);

        var bundle = new SetChannelKeysRequest.MemberKeyBundle(10L, "enckey", "wrappub");
        var request = new SetChannelKeysRequest(List.of(bundle));
        var response = chatController.setChannelKeys(1L, request, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(chatService).setChannelKeys(1L, request, 10L);
    }

    @Test
    void setChannelKeys_e2eDisabled_throwsNotFound() {
        when(chatProperties.e2eEnabled()).thenReturn(false);

        var bundle = new SetChannelKeysRequest.MemberKeyBundle(10L, "enckey", "wrappub");
        var request = new SetChannelKeysRequest(List.of(bundle));
        assertThatThrownBy(() -> chatController.setChannelKeys(1L, request, user))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- rotateChannelKeys (e2e disabled) ---

    @Test
    void rotateChannelKeys_e2eDisabled_throwsNotFound() {
        when(chatProperties.e2eEnabled()).thenReturn(false);

        var bundle = new SetChannelKeysRequest.MemberKeyBundle(10L, "enckey", "wrappub");
        var request = new SetChannelKeysRequest(List.of(bundle));
        assertThatThrownBy(() -> chatController.rotateChannelKeys(1L, request, user))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
