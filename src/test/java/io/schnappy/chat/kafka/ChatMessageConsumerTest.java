package io.schnappy.chat.kafka;

import io.schnappy.chat.dto.ChatMessageDto;
import io.schnappy.chat.repository.ScyllaMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatMessageConsumerTest {

    @Mock
    private ScyllaMessageRepository messageRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private ChatMessageConsumer chatMessageConsumer;

    // --- persistMessage ---

    @Test
    void persistMessage_savesMessageWithNullParent() {
        var message = ChatMessageDto.builder()
                .messageId(UUID.randomUUID().toString())
                .channelId(1L)
                .userId(10L)
                .username("alice")
                .content("Hello")
                .createdAt(Instant.now())
                .build();

        chatMessageConsumer.persistMessage(message);

        verify(messageRepository).saveMessage(eq(message), isNull());
    }

    @Test
    void persistMessage_savesMessageWithParentId() {
        UUID parentId = UUID.randomUUID();
        var message = ChatMessageDto.builder()
                .messageId(UUID.randomUUID().toString())
                .channelId(1L)
                .userId(10L)
                .username("alice")
                .content("Reply")
                .parentMessageId(parentId.toString())
                .createdAt(Instant.now())
                .build();

        chatMessageConsumer.persistMessage(message);

        verify(messageRepository).saveMessage(eq(message), eq(parentId));
    }

    @Test
    void persistMessage_runtimeException_rethrows() {
        var message = ChatMessageDto.builder()
                .messageId(UUID.randomUUID().toString())
                .channelId(1L)
                .userId(10L)
                .username("alice")
                .content("Hello")
                .createdAt(Instant.now())
                .build();

        doThrow(new RuntimeException("ScyllaDB down")).when(messageRepository).saveMessage(any(), any());

        assertThatThrownBy(() -> chatMessageConsumer.persistMessage(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ScyllaDB down");
    }

    // --- deliverMessage ---

    @Test
    void deliverMessage_sendsToCorrectTopic() {
        var message = ChatMessageDto.builder()
                .messageId(UUID.randomUUID().toString())
                .channelId(42L)
                .userId(10L)
                .username("alice")
                .content("Hello")
                .createdAt(Instant.now())
                .build();

        chatMessageConsumer.deliverMessage(message);

        verify(messagingTemplate).convertAndSend("/topic/channel.42", message);
    }

    @Test
    void deliverMessage_differentChannelIds_routeCorrectly() {
        var msg1 = ChatMessageDto.builder().channelId(1L).content("A").build();
        var msg2 = ChatMessageDto.builder().channelId(99L).content("B").build();

        chatMessageConsumer.deliverMessage(msg1);
        chatMessageConsumer.deliverMessage(msg2);

        verify(messagingTemplate).convertAndSend("/topic/channel.1", msg1);
        verify(messagingTemplate).convertAndSend("/topic/channel.99", msg2);
    }
}
