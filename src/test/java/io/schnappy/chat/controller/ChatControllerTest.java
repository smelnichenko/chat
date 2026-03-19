package io.schnappy.chat.controller;

import io.schnappy.chat.config.ChatProperties;
import io.schnappy.chat.dto.ChannelDto;
import io.schnappy.chat.dto.ChatMessageDto;
import io.schnappy.chat.dto.CreateChannelRequest;
import io.schnappy.chat.dto.SendMessageRequest;
import io.schnappy.chat.entity.Channel;
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

        var bundle = new io.schnappy.chat.dto.SetChannelKeysRequest.MemberKeyBundle(10L, "enckey", "wrappub");
        var request = new io.schnappy.chat.dto.SetChannelKeysRequest(List.of(bundle));
        var response = chatController.rotateChannelKeys(1L, request, user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("newKeyVersion", 3);
    }
}
