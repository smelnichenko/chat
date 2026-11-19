package io.schnappy.chat.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.connection.BusyConnectionException;
import com.datastax.oss.driver.api.core.connection.ClosedConnectionException;
import com.datastax.oss.driver.api.core.connection.ConnectionInitException;
import com.datastax.oss.driver.api.core.connection.HeartbeatException;
import com.datastax.oss.driver.api.core.servererrors.BootstrappingException;
import com.datastax.oss.driver.api.core.servererrors.OverloadedException;
import com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.UnavailableException;
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.datastax.oss.driver.api.core.type.codec.CodecNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerContainerRegistry;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.transaction.CannotCreateTransactionException;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Kafka record whose listener failed is never skipped. Spring's default tries it ten times within milliseconds, then
 * logs it and commits past it: a chat message read while ScyllaDB restarted was lost from the history (a user event
 * from the user cache). Here it is retried, blocking - the listener's one consumer thread waits, every partition it
 * reads with it (no retry topic: that reorders). Both listeners: chat.messages (ScyllaDB) and user.events (Redis, the
 * system channel in Postgres):
 * <ul>
 *   <li>its store down (ScyllaDB's nodes unreachable or overloaded, a connection refused, Postgres starting up): for
 *       as long as it is down - never to the
 *       .DLT, where it would come back out of order behind the events that followed it;</li>
 *   <li>any other failure: for KafkaRetryProperties' 15 min of waits, then {@code <topic>.DLT};</li>
 *   <li>no retry helps (not JSON - Spring's default list -, a reply to a parent id that is not one): the .DLT at
 *       once.</li>
 * </ul>
 * A dead letter carries the failure in one-line text headers - the nightly backup dumps every topic as text lines and
 * refuses a binary or multi-line header - and is counted ({@code kafka_dead_letters_total{topic}}: the
 * KafkaDeadLetters alert); one the .DLT refused is counted apart ({@code kafka_dead_letters_refused_total}) and
 * retried again, never committed. Boot wires the handler into every listener container. Its own profile, as
 * KafkaConfig's: Kafka's beans exist only there.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaRetryProperties.class)
@Profile("!test")
public class KafkaErrorHandlerConfig {

    static final String DEAD_LETTERS = "kafka.dead.letters";
    static final String DEAD_LETTERS_REFUSED = "kafka.dead.letters.refused";
    /** The original's place and time as text (Spring's own headers carry them binary: the backup refuses those). */
    static final String ORIGINAL_PARTITION = "dlt-original-partition";
    static final String ORIGINAL_OFFSET = "dlt-original-offset";
    static final String ORIGINAL_TIMESTAMP = "dlt-original-timestamp";
    /** The failure's message, on one line (Spring's is the raw message: a Postgres one spans lines). */
    static final String EXCEPTION_MESSAGE = "dlt-exception-message";
    private static final int MESSAGE_MAX = 1000;

    @Bean
    DefaultErrorHandler kafkaErrorHandler(DeadLetterTemplates templates, KafkaRetryProperties retry, MeterRegistry meters) {
        // no partition: Kafka picks one by the key, so the .DLT needs no more partitions than it has
        var deadLetters = new DeadLetterPublishingRecoverer(templates.byValueType(),
                (rec, ex) -> new TopicPartition(rec.topic() + ".DLT", -1));
        deadLetters.excludeHeader(HeadersToAdd.PARTITION, HeadersToAdd.OFFSET, HeadersToAdd.TS, HeadersToAdd.EX_MSG,
                HeadersToAdd.EX_STACKTRACE);
        deadLetters.addHeadersFunction(KafkaErrorHandlerConfig::textHeaders);
        var handler = new DefaultErrorHandler(recoverer(deadLetters, meters), retry.backOff());
        handler.setBackOffFunction((rec, ex) -> storeDown(ex) ? retry.unboundedBackOff() : null);
        // never retried: a reply naming no message, and a value the driver has no codec for - a column's type and the
        // Java type bound to it apart, a code bug no wait cures
        handler.addNotRetryableExceptions(InvalidChatMessageException.class, CodecNotFoundException.class);
        return handler;
    }

    /**
     * The templates dead letters go out on - a value that never deserialized as the bytes it came as, one that did as
     * JSON again - closed with the context (each made with config overrides has a producer of its own). A send gives up
     * in 15 s (the producer's default: two minutes, the listener held all that time) and the record is retried again.
     */
    @Bean
    DeadLetterTemplates deadLetterTemplates(ProducerFactory<Object, Object> producerFactory) {
        return new DeadLetterTemplates(producerFactory);
    }

    static final class DeadLetterTemplates implements DisposableBean {
        private final KafkaTemplate<Object, Object> raw;
        private final KafkaTemplate<Object, Object> json;

        DeadLetterTemplates(ProducerFactory<Object, Object> producerFactory) {
            Map<String, Object> bounded = Map.of(
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000,
                    ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000,
                    ProducerConfig.MAX_BLOCK_MS_CONFIG, 15_000);
            Map<String, Object> rawConfig = new LinkedHashMap<>(bounded);
            rawConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
            raw = new KafkaTemplate<>(producerFactory, rawConfig);
            json = new KafkaTemplate<>(producerFactory, bounded);
        }

        Map<Class<?>, KafkaOperations<?, ?>> byValueType() {
            Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
            templates.put(byte[].class, raw);
            templates.put(Object.class, json);
            return templates;
        }

        @Override
        public void destroy() {
            raw.destroy();
            json.destroy();
        }
    }

    /**
     * The value deserializer wrapped in an ErrorHandlingDeserializer, whatever the configuration names: a value that is
     * not JSON then fails in the wrapper, not in the poll - the handler keeps it on the .DLT and the partition moves
     * on. A poll that fails is no record the handler can recover: the listener polls that offset for ever. Chat's
     * application.yml names the wrapper already; this keeps it so if a deployment's configuration names the bare
     * JsonDeserializer (monitor's ConfigMap did).
     */
    @Bean
    DefaultKafkaConsumerFactoryCustomizer valueDeserializerWrapped() {
        return factory -> {
            Object named = factory.getConfigurationProperties().get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
            if (named != null && !isErrorHandling(named)) {
                factory.updateConfigs(Map.of(
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class,
                        ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, named));
            }
        };
    }

    /**
     * Each listener topic's two counters, at 0 from the start: the KafkaDeadLetters alert reads their increase, which a
     * series made at its first count would hide.
     */
    @Bean
    ApplicationListener<ApplicationStartedEvent> deadLetterCountersAtZero(ListenerContainerRegistry registry,
                                                                         MeterRegistry meters) {
        return event -> registry.getListenerContainers().forEach(container -> {
            String[] topics = container.getContainerProperties().getTopics();
            for (String topic : topics == null ? new String[0] : topics) {
                counter(meters, DEAD_LETTERS, topic);
                counter(meters, DEAD_LETTERS_REFUSED, topic);
            }
        });
    }

    static boolean isErrorHandling(Object deserializer) {
        return deserializer == ErrorHandlingDeserializer.class
                || ErrorHandlingDeserializer.class.getName().equals(deserializer);
    }

    /**
     * Whether the failure is a store being away - ScyllaDB's nodes unreachable, timing out, overloaded or starting (the
     * driver's own exceptions: chat writes it through a CqlSession, not Spring); Redis or Postgres refusing a
     * connection, a transaction not begun, Postgres starting up or shutting down (SQLState classes 08 and 57P), out of
     * connections (53) - which waiting cures, as against a failure no wait does.
     */
    static boolean storeDown(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (scyllaAway(t)) {
                return true;
            }
            if (t instanceof DataAccessResourceFailureException || t instanceof TransientDataAccessException
                    || t instanceof RecoverableDataAccessException || t instanceof CannotCreateTransactionException
                    || t instanceof SQLTransientException || t instanceof SQLRecoverableException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null && (sql.getSQLState().startsWith("08")
                    || sql.getSQLState().startsWith("57P") || sql.getSQLState().startsWith("53"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean scyllaAway(Throwable t) {
        return t instanceof AllNodesFailedException || t instanceof DriverTimeoutException
                || t instanceof ClosedConnectionException || t instanceof ConnectionInitException
                || t instanceof HeartbeatException || t instanceof BusyConnectionException
                || t instanceof UnavailableException || t instanceof ReadTimeoutException
                || t instanceof WriteTimeoutException || t instanceof OverloadedException
                || t instanceof BootstrappingException;
    }

    /** The original's partition, offset and timestamp, and the failure's message, as one-line text headers. */
    static Headers textHeaders(ConsumerRecord<?, ?> rec, Exception failure) {
        var headers = new RecordHeaders();
        headers.add(ORIGINAL_PARTITION, text(String.valueOf(rec.partition())));
        headers.add(ORIGINAL_OFFSET, text(String.valueOf(rec.offset())));
        headers.add(ORIGINAL_TIMESTAMP, text(String.valueOf(rec.timestamp())));
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        headers.add(EXCEPTION_MESSAGE, text(oneLine(String.valueOf(cause.getMessage()))));
        return headers;
    }

    /** Control characters (newlines, tabs, the backup's 0x1E) and runs of spaces one space; at most MESSAGE_MAX long. */
    static String oneLine(String message) {
        String flat = message.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        return flat.length() > MESSAGE_MAX ? flat.substring(0, MESSAGE_MAX) : flat;
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** The record on its .DLT, then counted and logged - or, the .DLT refusing it, counted as refused and rethrown. */
    static ConsumerAwareRecordRecoverer recoverer(DeadLetterPublishingRecoverer deadLetters, MeterRegistry meters) {
        return (rec, consumer, ex) -> {
            try {
                deadLetters.accept(rec, consumer, ex);
            } catch (RuntimeException refused) {
                // retried again by the handler, never committed: the listener holds here till the .DLT takes it
                counter(meters, DEAD_LETTERS_REFUSED, rec.topic()).increment();
                throw refused;
            }
            try {
                counter(meters, DEAD_LETTERS, rec.topic()).increment();
                log.error("Kafka record {}-{}@{} given up on, kept on {}.DLT", rec.topic(), rec.partition(),
                        rec.offset(), rec.topic(), ex);
            } catch (RuntimeException afterPublish) {
                // the record is on the .DLT: a throw here would count as its recovery failing - published again
                log.warn("Kafka record {}-{}@{} kept on its .DLT, not counted", rec.topic(), rec.partition(),
                        rec.offset(), afterPublish);
            }
        };
    }

    private static Counter counter(MeterRegistry meters, String name, String topic) {
        return Counter.builder(name)
                .description(DEAD_LETTERS.equals(name) ? "Kafka records given up on, kept on their topic's .DLT"
                        : "Kafka records their topic's .DLT refused - retried, never committed")
                .tag("topic", topic)
                .register(meters);
    }
}
