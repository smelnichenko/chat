package io.schnappy.chat.kafka;

import io.schnappy.chat.dto.ChatMessageDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import org.springframework.kafka.support.SendResult;

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

    @Test
    void sendMessage_kafkaFailure_logsError() {
        var message = ChatMessageDto.builder()
                .messageId("msg-3")
                .channelId(5L)
                .content("Fail")
                .build();

        var future = new CompletableFuture<SendResult<String, ChatMessageDto>>();
        when(kafkaTemplate.send("chat.messages", "5", message)).thenReturn(future);

        chatKafkaProducer.sendMessage(message);

        // Complete exceptionally to trigger the error callback
        future.completeExceptionally(new RuntimeException("Kafka unavailable"));

        // Verify the send was called — the error is logged, not thrown
        verify(kafkaTemplate).send("chat.messages", "5", message);
    }

    @Test
    void sendMessage_kafkaSuccess_noError() {
        var message = ChatMessageDto.builder()
                .messageId("msg-4")
                .channelId(3L)
                .content("OK")
                .build();

        var future = new CompletableFuture<SendResult<String, ChatMessageDto>>();
        when(kafkaTemplate.send("chat.messages", "3", message)).thenReturn(future);

        chatKafkaProducer.sendMessage(message);

        // Complete successfully
        future.complete(null);

        verify(kafkaTemplate).send("chat.messages", "3", message);
    }
}
