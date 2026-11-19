package io.schnappy.chat.kafka;

import io.schnappy.chat.dto.ChatMessageDto;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A chat message's send is waited for: the broker took it, or the caller fails (the request a 503, a listener's record
 * retried) - never logged and lost behind a 201.
 */
class ChatKafkaProducerTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, ChatMessageDto> kafkaTemplate = mock(KafkaTemplate.class);
    private final ChatKafkaProducer producer = new ChatKafkaProducer(kafkaTemplate, Duration.ofMillis(200));

    @Test
    void sendMessage_taken_toItsTopic_keyedByItsChannel() {
        var message = message(42L);
        when(kafkaTemplate.send("chat.messages", "42", message)).thenReturn(CompletableFuture.completedFuture(null));

        assertThatCode(() -> producer.sendMessage(message)).doesNotThrowAnyException();

        verify(kafkaTemplate).send("chat.messages", "42", message);
    }

    @Test
    void sendMessage_refused_theCallerFails() {
        var message = message(5L);
        var refused = new CompletableFuture<SendResult<String, ChatMessageDto>>();
        refused.completeExceptionally(new IllegalStateException("Kafka unavailable"));
        when(kafkaTemplate.send("chat.messages", "5", message)).thenReturn(refused);

        assertThatThrownBy(() -> producer.sendMessage(message))
                .isInstanceOf(MessageNotSentException.class)
                .hasMessageContaining(message.getMessageId()).hasMessageContaining("Kafka unavailable")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void sendMessage_noAnswerInItsWait_theCallerFails() {
        var message = message(7L);
        when(kafkaTemplate.send("chat.messages", "7", message)).thenReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> producer.sendMessage(message))
                .isInstanceOf(MessageNotSentException.class).hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void theWait_outlastsTheProducersDeliveryTimeout() {
        // application.yml's delivery.timeout.ms (15 s): the send has ended, taken or refused, before the caller is told
        assertThat(ChatKafkaProducer.SEND_WAIT).isGreaterThan(Duration.ofSeconds(15));
    }

    private static ChatMessageDto message(long channelId) {
        return ChatMessageDto.builder().messageId(UUID.randomUUID().toString()).channelId(channelId)
                .userUuid(UUID.randomUUID()).username("alice").content("Hello").createdAt(Instant.now()).build();
    }
}
