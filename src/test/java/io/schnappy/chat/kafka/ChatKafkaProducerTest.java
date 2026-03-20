package io.schnappy.chat.kafka;

import io.schnappy.chat.dto.ChatMessageDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatKafkaProducerTest {

    @Mock
    private KafkaTemplate<String, ChatMessageDto> kafkaTemplate;

    @InjectMocks
    private ChatKafkaProducer chatKafkaProducer;

    @Test
    void sendMessage_sendsToCorrectTopicWithChannelIdAsKey() {
        var message = ChatMessageDto.builder()
                .messageId("msg-1")
                .channelId(42L)
                .userId(10L)
                .username("alice")
                .content("Hello")
                .createdAt(Instant.now())
                .build();

        when(kafkaTemplate.send("chat.messages", "42", message))
                .thenReturn(new CompletableFuture<>());

        chatKafkaProducer.sendMessage(message);

        verify(kafkaTemplate).send("chat.messages", "42", message);
    }

    @Test
    void sendMessage_differentChannelId_usesCorrectKey() {
        var message = ChatMessageDto.builder()
                .messageId("msg-2")
                .channelId(7L)
                .content("Test")
                .build();

        when(kafkaTemplate.send("chat.messages", "7", message))
                .thenReturn(new CompletableFuture<>());

        chatKafkaProducer.sendMessage(message);

        verify(kafkaTemplate).send("chat.messages", "7", message);
    }
}
