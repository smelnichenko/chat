package io.schnappy.chat.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Kafka record whose listener failed is never skipped. Spring's default tries it ten times within milliseconds, then
 * logs it and commits past it: a chat message read while ScyllaDB restarted was lost from the history. Here it is
 * retried with KafkaRetryProperties' back-off, blocking its partition (no retry topic: that reorders), and one that
 * still fails goes to {@code <topic>.DLT} with the failure in its headers. A record no retry can help - it does not
 * deserialize (Spring's default list), or replies to a parent id that is not one ({@link InvalidChatMessageException})
 * - goes there at once. Each one counted: {@code kafka_dead_letters_total{topic}}, what the KafkaDeadLetters alert
 * reads. Boot wires the handler into every listener container: chat.messages (the history, ScyllaDB) and user.events
 * (the user cache). Its own profile, as KafkaConfig's: Kafka's beans exist only there.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaRetryProperties.class)
@Profile("!test")
public class KafkaErrorHandlerConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(ProducerFactory<Object, Object> producerFactory, KafkaRetryProperties retry,
                                          MeterRegistry meters) {
        // a value that never deserialized comes back as its original bytes (ErrorHandlingDeserializer) and is published
        // as they came; a value that did is published as JSON again
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, new KafkaTemplate<>(producerFactory,
                Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)));
        templates.put(Object.class, new KafkaTemplate<>(producerFactory));
        // no partition: Kafka picks one by the key, so the .DLT needs no more partitions than it has
        var deadLetters = new DeadLetterPublishingRecoverer(templates,
                (rec, ex) -> new TopicPartition(rec.topic() + ".DLT", -1));
        ConsumerAwareRecordRecoverer recoverer = (rec, consumer, ex) -> {
            // throws when the .DLT refused it: the record is then retried again, never committed
            deadLetters.accept(rec, consumer, ex);
            Counter.builder("kafka.dead.letters")
                    .description("Kafka records given up on, kept on their topic's .DLT")
                    .tag("topic", rec.topic())
                    .register(meters)
                    .increment();
            log.error("Kafka record {}-{}@{} given up on, kept on {}.DLT", rec.topic(), rec.partition(), rec.offset(),
                    rec.topic(), ex);
        };
        var handler = new DefaultErrorHandler(recoverer, retry.backOff());
        handler.addNotRetryableExceptions(InvalidChatMessageException.class);
        return handler;
    }
}
