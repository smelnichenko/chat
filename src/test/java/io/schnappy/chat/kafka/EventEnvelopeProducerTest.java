package io.schnappy.chat.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventEnvelopeProducerTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private EventEnvelopeProducer producer;

    @Captor
    private ArgumentCaptor<ProducerRecord<String, Object>> recordCaptor;

    @Test
    void publish_sendsToTopicWithKeyAndChannelsHeader() {
        var envelope = EventEnvelope.of("chat.message", "room:1", UUID.randomUUID().toString(), "body");
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        producer.publish("chat:room:1", "1", envelope);

        verify(kafkaTemplate).send(recordCaptor.capture());
        ProducerRecord<String, Object> sent = recordCaptor.getValue();
        assertThat(sent.topic()).isEqualTo(EventEnvelopeProducer.TOPIC);
        assertThat(sent.key()).isEqualTo("1");
        assertThat(sent.value()).isEqualTo(envelope);
        var header = sent.headers().lastHeader(EventEnvelopeProducer.CHANNELS_HEADER);
        assertThat(header).isNotNull();
        assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo("chat:room:1");
    }

    @Test
    void publish_kafkaFailure_logsErrorAndDoesNotThrow() {
        var envelope = EventEnvelope.of("chat.message", "room:2", "actor", "body");
        var future = new CompletableFuture<SendResult<String, Object>>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        producer.publish("chat:room:2", "2", envelope);

        // Exceptional completion drives the error branch of the whenComplete callback.
        future.completeExceptionally(new RuntimeException("broker down"));

        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @Test
    void publish_kafkaSuccess_takesNoErrorBranch() {
        var envelope = EventEnvelope.of("chat.message", "room:3", "actor", "body");
        var future = new CompletableFuture<SendResult<String, Object>>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        producer.publish("chat:room:3", "3", envelope);

        future.complete(null);

        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }
}
