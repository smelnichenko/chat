package io.schnappy.chat.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventEnvelopeProducer {

    static final String TOPIC = "events.chat.messages";
    static final String CHANNELS_HEADER = "x-centrifugo-channels";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(String channel, String key, EventEnvelope envelope) {
        var record = new ProducerRecord<String, Object>(TOPIC, null, key, envelope);
        record.headers().add(new RecordHeader(
            CHANNELS_HEADER, channel.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish envelope for channel {}: {}", channel, ex.getMessage());
            }
        });
    }
}
