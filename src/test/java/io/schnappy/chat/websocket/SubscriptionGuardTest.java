package io.schnappy.chat.websocket;

import io.schnappy.chat.service.ChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionGuardTest {

    private static final UUID USER_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Mock
    private ChatService chatService;

    @InjectMocks
    private SubscriptionGuard subscriptionGuard;

    private SessionSubscribeEvent createSubscribeEvent(String destination, Map<String, Object> sessionAttrs) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSessionId("test-session");
        accessor.setSessionAttributes(sessionAttrs);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionSubscribeEvent(this, message);
    }

    @Test
    void onSubscribe_memberOfChannel_allowed() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userUuid", USER_UUID);

        when(chatService.isMember(1L, USER_UUID)).thenReturn(true);

        var event = createSubscribeEvent("/topic/channel.1", attrs);

        // Should not throw
        subscriptionGuard.onSubscribe(event);

        verify(chatService).isMember(1L, USER_UUID);
    }

    @Test
    void onSubscribe_notMemberOfChannel_throws() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userUuid", USER_UUID);

        when(chatService.isMember(1L, USER_UUID)).thenReturn(false);

        var event = createSubscribeEvent("/topic/channel.1", attrs);

        assertThatThrownBy(() -> subscriptionGuard.onSubscribe(event))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Not a member of channel 1");
    }

    @Test
    void onSubscribe_nullUserUuid_throws() {
        Map<String, Object> attrs = new HashMap<>();
        // No userUuid in attrs

        var event = createSubscribeEvent("/topic/channel.1", attrs);

        assertThatThrownBy(() -> subscriptionGuard.onSubscribe(event))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void onSubscribe_nullSessionAttributes_throws() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/channel.1");
        accessor.setSessionId("test-session");
        // Don't set session attributes
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        var event = new SessionSubscribeEvent(this, message);

        assertThatThrownBy(() -> subscriptionGuard.onSubscribe(event))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void onSubscribe_nullDestination_doesNothing() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("test-session");
        // No destination set
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        var event = new SessionSubscribeEvent(this, message);

        // Should not throw, should not call chatService
        subscriptionGuard.onSubscribe(event);

        verify(chatService, never()).isMember(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(UUID.class));
    }

    @Test
    void onSubscribe_nonChannelDestination_doesNothing() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userUuid", USER_UUID);

        var event = createSubscribeEvent("/topic/other-topic", attrs);

        // Should not throw, should not call chatService
        subscriptionGuard.onSubscribe(event);

        verify(chatService, never()).isMember(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(UUID.class));
    }
}
