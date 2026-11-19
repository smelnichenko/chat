package io.schnappy.chat.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.schnappy.chat.config.KafkaConfig;
import io.schnappy.chat.dto.ChatMessageDto;
import io.schnappy.chat.repository.ScyllaMessageRepository;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The chat-persistence listener against a real broker, with production's Kafka settings (application.yml) and error
 * handler (KafkaErrorHandlerConfig) - only the back-off shortened. ScyllaDB down for a while costs no message; down
 * past the retries, a reply to a parent id that is not one and a value that is not JSON each end on
 * chat.messages.DLT, and the partition moves on.
 */
@SpringBootTest(
        classes = {KafkaConfig.class, KafkaErrorHandlerConfig.class, ChatMessageConsumer.class,
                ChatMessageRetryKafkaTest.Deliveries.class, SimpleMeterRegistry.class},
        properties = {
                // waits of 10 ms doubling to 50 ms, 600 ms of them in all - some 14 tries (production: 1 s to 30 s,
                // 15 min). Each retry costs the embedded broker ~0.5 s of its own (a fetch after the seek), so a test
                // timed against the back-off cannot tell it from Spring's default: the outage is counted in tries
                "chat.kafka.retry.initial-interval=10ms",
                "chat.kafka.retry.max-interval=50ms",
                "chat.kafka.retry.max-elapsed=600ms"
        })
// profile "local", not "test": KafkaConfig and KafkaErrorHandlerConfig run outside the test profile only, as in
// production. It outranks CI's SPRING_PROFILES_ACTIVE, and logback's console appender shows the retries.
@ActiveProfiles("local")
// chat.messages of three partitions; its .DLT made in open() with one - as the broker makes it on first use: a dead
// letter from partition 2 must still find its place there (the handler leaves the partition to Kafka)
@EmbeddedKafka(partitions = 3, topics = ChatMessageRetryKafkaTest.TOPIC,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
// a deserializer that fails every poll hangs the listener and fills the heap: a case fails in 90 s, the build goes on
@Timeout(90)
// the broker and the listener stop with the class: a cached context would keep them polling through the suite
@DirtiesContext
class ChatMessageRetryKafkaTest {

    static final String TOPIC = "chat.messages";
    static final String DLT = "chat.messages.DLT";
    /** ChatKafkaProducer's key: the channel. */
    private static final String CHANNEL = "7";
    private static final Duration WAIT = Duration.ofSeconds(30);
    /** More tries than Spring's default handler makes (ten), fewer than the test's back-off allows (some 14). */
    private static final int FAILED_TRIES = 12;

    @MockitoBean
    private ScyllaMessageRepository messageRepository;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private Deliveries deliveries;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    private Producer<String, byte[]> producer;
    private Consumer<String, byte[]> dltReader;
    private final List<ConsumerRecord<String, byte[]>> deadLetters = new ArrayList<>();

    /** How many times the listener was handed each chat.messages record (partition@offset): a retry hands it again. */
    @TestComponent
    static class Deliveries implements RecordInterceptor<Object, Object> {
        private final Map<String, AtomicInteger> byRecord = new ConcurrentHashMap<>();

        @Override
        public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> rec,
                                                        Consumer<Object, Object> consumer) {
            byRecord.computeIfAbsent(rec.partition() + "@" + rec.offset(), k -> new AtomicInteger()).incrementAndGet();
            return rec;
        }

        int of(String at) {
            AtomicInteger n = byRecord.get(at);
            return n == null ? 0 : n.get();
        }
    }

    @BeforeEach
    void open() throws Exception {
        var adminConfig = Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        try (Admin admin = Admin.create(adminConfig)) {
            admin.createTopics(List.of(new NewTopic(DLT, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw e;
            }
        }
        producer = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()),
                new StringSerializer(), new ByteArraySerializer());
        dltReader = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer());
        dltReader.subscribe(List.of(DLT));
    }

    @AfterEach
    void close() {
        producer.close();
        dltReader.close();
    }

    @Test
    void scyllaDownForAWhile_theMessageStoredOnceItIsBack() throws Exception {
        String content = "restart-" + UUID.randomUUID();
        AtomicInteger tries = new AtomicInteger();
        AtomicInteger stored = new AtomicInteger();
        doAnswer(inv -> {
            if (tries.incrementAndGet() <= FAILED_TRIES) {
                // any failure the driver throws while ScyllaDB restarts - a retryable one
                throw new DataAccessResourceFailureException("ScyllaDB restarting");
            }
            stored.incrementAndGet();
            return UUID.randomUUID();
        }).when(messageRepository).saveMessage(withContent(content), isNull());

        String at = send(message(content, null));

        await().atMost(WAIT).untilAsserted(() -> assertThat(stored).hasValue(1));
        assertThat(tries).hasValue(FAILED_TRIES + 1);
        assertThat(deliveries.of(at)).as("stored on the try after the failed ones").isEqualTo(FAILED_TRIES + 1);
    }

    @Test
    void scyllaDownPastTheRetries_theMessageKeptOnTheDlt_andThePartitionMovesOn() throws Exception {
        String content = "gone-" + UUID.randomUUID();
        double before = deadLettersCounted();
        doThrow(new DataAccessResourceFailureException("ScyllaDB gone"))
                .when(messageRepository).saveMessage(withContent(content), any());

        String at = send(message(content, null));

        ConsumerRecord<String, byte[]> dead = deadLetter(content);
        assertThat(dead.key()).isEqualTo(CHANNEL);
        assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(TOPIC);
        assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)).isEqualTo("chat-persistence");
        assertThat(failure(dead)).contains(DataAccessResourceFailureException.class.getName());
        assertThat(deliveries.of(at)).as("retried past Spring's default ten tries before it was given up on")
                .isGreaterThan(FAILED_TRIES);
        // counted for the alert - just after the .DLT took it
        await().atMost(WAIT).untilAsserted(() -> assertThat(deadLettersCounted()).isGreaterThanOrEqualTo(before + 1));

        String next = "next-" + UUID.randomUUID();
        send(message(next, null));
        verify(messageRepository, timeout(WAIT.toMillis())).saveMessage(withContent(next), isNull());
    }

    @Test
    void aReplyToAParentIdThatIsNotOne_keptOnTheDltAtOnce() throws Exception {
        String content = "orphan-" + UUID.randomUUID();
        String at = send(message(content, "not-a-message-id"));

        ConsumerRecord<String, byte[]> dead = deadLetter(content);
        assertThat(failure(dead)).contains(InvalidChatMessageException.class.getName());
        assertThat(deliveries.of(at)).as("never retried").isEqualTo(1);
        verify(messageRepository, never()).saveMessage(withContent(content), any());
    }

    @Test
    void wiredAsInProduction_theHandlerThere_noTestProfile() {
        // CI runs with SPRING_PROFILES_ACTIVE=test, and KafkaConfig and the handler run outside the test profile only:
        // this class's own profile must win, or every case below waits out its timeout for no listener
        assertThat(environment.getActiveProfiles()).contains("local").doesNotContain("test", "ci");
        assertThat(context.containsBean("kafkaErrorHandler")).isTrue();
    }

    @Test
    void aValueThatIsNotJson_keptOnTheDltAsItCame_andThePartitionMovesOn() throws Exception {
        // on partition 2 - its .DLT has one: the dead letter goes where Kafka puts it, not to a partition 2 it lacks
        String poison = "not json " + UUID.randomUUID();
        send(2, poison);
        String next = "after-poison-" + UUID.randomUUID();
        send(2, message(next, null));

        ConsumerRecord<String, byte[]> dead = deadLetter(poison);
        assertThat(dead.value()).as("its original bytes, not JSON of them").isEqualTo(poison.getBytes(UTF_8));
        assertThat(failure(dead)).contains(DeserializationException.class.getName());
        verify(messageRepository, timeout(WAIT.toMillis())).saveMessage(withContent(next), isNull());
    }

    /** A record as ChatKafkaProducer sends it: keyed by its channel, Spring's type header naming the DTO. */
    private String send(String value) throws Exception {
        return send(null, value);
    }

    /** The record sent to that partition (null: by its key), as partition@offset. */
    private String send(Integer partition, String value) throws Exception {
        var rec = new ProducerRecord<>(TOPIC, partition, CHANNEL, value.getBytes(UTF_8));
        rec.headers().add("__TypeId__", ChatMessageDto.class.getName().getBytes(UTF_8));
        RecordMetadata sent = producer.send(rec).get(10, TimeUnit.SECONDS);
        return sent.partition() + "@" + sent.offset();
    }

    private static String message(String content, String parentMessageId) {
        String parent = parentMessageId == null ? "null" : "\"" + parentMessageId + "\"";
        return """
                {"messageId":"%s","channelId":%s,"userUuid":"%s","username":"alice","content":"%s","parentMessageId":%s}"""
                .formatted(UUID.randomUUID(), CHANNEL, UUID.randomUUID(), content, parent);
    }

    private static ChatMessageDto withContent(String content) {
        return argThat(m -> m != null && content.equals(m.getContent()));
    }

    /** The .DLT record whose value holds the marker. */
    private ConsumerRecord<String, byte[]> deadLetter(String marker) {
        await().atMost(WAIT).pollInSameThread().until(() -> {
            dltReader.poll(Duration.ofMillis(200)).forEach(deadLetters::add);
            return deadLetters.stream().anyMatch(r -> holds(r, marker));
        });
        return deadLetters.stream().filter(r -> holds(r, marker)).findFirst().orElseThrow();
    }

    /** kafka_dead_letters_total{topic="chat.messages"}, what the KafkaDeadLetters alert reads. */
    private double deadLettersCounted() {
        Counter counter = meters.find("kafka.dead.letters").tag("topic", TOPIC).counter();
        return counter == null ? 0 : counter.count();
    }

    private static boolean holds(ConsumerRecord<String, byte[]> rec, String marker) {
        return rec.value() != null && new String(rec.value(), UTF_8).contains(marker);
    }

    /** The failure's exception and its cause, as the .DLT record's headers name them. */
    private static String failure(ConsumerRecord<?, ?> rec) {
        return header(rec, KafkaHeaders.DLT_EXCEPTION_FQCN) + " " + header(rec, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
    }

    private static String header(ConsumerRecord<?, ?> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), UTF_8);
    }
}
