package io.schnappy.chat.controller;

import io.schnappy.chat.dto.SendMessageRequest;
import io.schnappy.chat.service.ChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatWebSocketControllerTest {

    @Mock
    private ChatService chatService;

    @Mock
    private SimpMessageHeaderAccessor headerAccessor;

    @InjectMocks
    private ChatWebSocketController controller;

    // --- sendMessage ---

    @Test
    void sendMessage_validPayload_delegatesToChatService() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", 10L);
        attrs.put("username", "alice@example.com");
        when(headerAccessor.getSessionAttributes()).thenReturn(attrs);
        when(chatService.isMember(1L, 10L)).thenReturn(true);

        Map<String, Object> payload = Map.of("channelId", 1L, "content", "Hello");

        controller.sendMessage(payload, headerAccessor);

        verify(chatService).sendMessage(eq(1L), any(SendMessageRequest.class), eq(10L), eq("alice@example.com"));
    }

    @Test
    void sendMessage_withParentMessageId_includesItInRequest() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", 10L);
        attrs.put("username", "alice@example.com");
        when(headerAccessor.getSessionAttributes()).thenReturn(attrs);
        when(chatService.isMember(1L, 10L)).thenReturn(true);

        Map<String, Object> payload = Map.of("channelId", 1L, "content", "Reply", "parentMessageId", "parent-uuid");

        controller.sendMessage(payload, headerAccessor);

        verify(chatService).sendMessage(eq(1L), any(SendMessageRequest.class), eq(10L), eq("alice@example.com"));
    }

    @Test
    void sendMessage_withKeyVersion_includesItInRequest() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", 10L);
        attrs.put("username", "alice@example.com");
        when(headerAccessor.getSessionAttributes()).thenReturn(attrs);
        when(chatService.isMember(1L, 10L)).thenReturn(true);

        Map<String, Object> payload = Map.of("channelId", 1L, "content", "Encrypted", "keyVersion", 2);

        controller.sendMessage(payload, headerAccessor);

        verify(chatService).sendMessage(eq(1L), any(SendMessageRequest.class), eq(10L), eq("alice@example.com"));
    }

    @Test
    void sendMessage_nullSessionAttributes_doesNothing() {
        when(headerAccessor.getSessionAttributes()).thenReturn(null);

        controller.sendMessage(Map.of("channelId", 1L, "content", "Hello"), headerAccessor);

        verify(chatService, never()).sendMessage(anyLong(), any(), anyLong(), anyString());
    }

    @Test
    void sendMessage_nullUserId_doesNothing() {
        Map<String, Object> attrs = new HashMap<>();
        // no userId
        when(headerAccessor.getSessionAttributes()).thenReturn(attrs);

        controller.sendMessage(Map.of("channelId", 1L, "content", "Hello"), headerAccessor);

        verify(chatService, never()).sendMessage(anyLong(), any(), anyLong(), anyString());
    }

    @Test
    void sendMessage_nullChannelId_doesNothing() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", 10L);
        attrs.put("username", "alice@example.com");
        when(headerAccessor.getSessionAttributes()).thenReturn(attrs);

        Map<String, Object> payload = new HashMap<>();
        payload.put("channelId", null);
        payload.put("content", "Hello");

        controller.sendMessage(payload, headerAccessor);

        verify(chatService, never()).sendMessage(anyLong(), any(), anyLong(), anyString());
    }

    @Test
    void sendMessage_nullContent_doesNothing() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", 10L);
        attrs.put("username", "alice@example.com");
        when(headerAccessor.getSessionAttributes()).thenReturn(attrs);

        Map<String, Object> payload = new HashMap<>();
        payload.put("channelId", 1L);
        payload.put("content", null);

        controller.sendMessage(payload, headerAccessor);

        verify(chatService, never()).sendMessage(anyLong(), any(), anyLong(), anyString());
    }

    @Test
    void sendMessage_blankContent_doesNothing() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", 10L);
        attrs.put("username", "alice@example.com");
        when(headerAccessor.getSessionAttributes()).thenReturn(attrs);

        Map<String, Object> payload = Map.of("channelId", 1L, "content", "   ");

        controller.sendMessage(payload, headerAccessor);

        verify(chatService, never()).sendMessage(anyLong(), any(), anyLong(), anyString());
    }

    @Test
    void sendMessage_contentTooLong_doesNothing() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", 10L);
        attrs.put("username", "alice@example.com");
        when(headerAccessor.getSessionAttributes()).thenReturn(attrs);

        String longContent = "x".repeat(4001);
        Map<String, Object> payload = Map.of("channelId", 1L, "content", longContent);

        controller.sendMessage(payload, headerAccessor);

        verify(chatService, never()).sendMessage(anyLong(), any(), anyLong(), anyString());
    }

    @Test
    void sendMessage_notMember_doesNotSend() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", 10L);
        attrs.put("username", "alice@example.com");
        when(headerAccessor.getSessionAttributes()).thenReturn(attrs);
        when(chatService.isMember(1L, 10L)).thenReturn(false);

        Map<String, Object> payload = Map.of("channelId", 1L, "content", "Hello");

        controller.sendMessage(payload, headerAccessor);

        verify(chatService, never()).sendMessage(anyLong(), any(), anyLong(), anyString());
    }

    // --- markAsRead ---

    @Test
    void markAsRead_validPayload_delegatesToChatService() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", 10L);
        when(headerAccessor.getSessionAttributes()).thenReturn(attrs);

        Map<String, Object> payload = Map.of("channelId", 5L);

        controller.markAsRead(payload, headerAccessor);

        verify(chatService).updateLastRead(5L, 10L);
    }

    @Test
    void markAsRead_nullSessionAttributes_doesNothing() {
        when(headerAccessor.getSessionAttributes()).thenReturn(null);

        controller.markAsRead(Map.of("channelId", 5L), headerAccessor);

        verify(chatService, never()).updateLastRead(anyLong(), anyLong());
    }

    @Test
    void markAsRead_nullUserId_doesNothing() {
        Map<String, Object> attrs = new HashMap<>();
        when(headerAccessor.getSessionAttributes()).thenReturn(attrs);

        controller.markAsRead(Map.of("channelId", 5L), headerAccessor);

        verify(chatService, never()).updateLastRead(anyLong(), anyLong());
    }

    @Test
    void markAsRead_nullChannelId_doesNothing() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", 10L);
        when(headerAccessor.getSessionAttributes()).thenReturn(attrs);

        Map<String, Object> payload = new HashMap<>();
        payload.put("channelId", null);

        controller.markAsRead(payload, headerAccessor);

        verify(chatService, never()).updateLastRead(anyLong(), anyLong());
    }
}
