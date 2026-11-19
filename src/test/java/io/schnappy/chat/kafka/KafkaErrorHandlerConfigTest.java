package io.schnappy.chat.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.NoNodeAvailableException;
import com.datastax.oss.driver.api.core.connection.BusyConnectionException;
import com.datastax.oss.driver.api.core.connection.ClosedConnectionException;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.connection.ConnectionInitException;
import com.datastax.oss.driver.api.core.connection.HeartbeatException;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.servererrors.BootstrappingException;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import com.datastax.oss.driver.api.core.servererrors.OverloadedException;
import com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.SyntaxError;
import com.datastax.oss.driver.api.core.servererrors.UnavailableException;
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.WriteType;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.core.type.codec.CodecNotFoundException;
import com.datastax.oss.driver.api.core.type.reflect.GenericType;
import org.apache.kafka.common.header.Headers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.transaction.CannotCreateTransactionException;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaErrorHandlerConfigTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DeadLetterPublishingRecoverer deadLetters = mock(DeadLetterPublishingRecoverer.class);
    private final ConsumerRecord<Object, Object> rec = new ConsumerRecord<>("user.events", 2, 7L, "USER_CREATED", "{}");
    private final ConsumerAwareRecordRecoverer recoverer = KafkaErrorHandlerConfig.recoverer(deadLetters, meters);

    @Test
    void aDeadLetterTheDltTook_countedOnce_neverAsRefused() {
        var failure = new IllegalStateException("Postgres gone");

        recoverer.accept(rec, null, failure);

        verify(deadLetters).accept(eq(rec), isNull(), eq(failure));
        assertThat(counted(KafkaErrorHandlerConfig.DEAD_LETTERS)).isEqualTo(1);
        assertThat(counted(KafkaErrorHandlerConfig.DEAD_LETTERS_REFUSED)).isZero();
    }

    @Test
    void aDeadLetterTheDltRefused_rethrown_countedAsRefused_neverAsKept() {
        // rethrown, the handler retries the record again and never commits it: no "kept" for the alert to read
        var refused = new KafkaException("user.events.DLT not there");
        doThrow(refused).when(deadLetters).accept(any(), any(), any());
        var failure = new IllegalStateException("Postgres gone");

        assertThatThrownBy(() -> recoverer.accept(rec, null, failure)).isSameAs(refused);
        assertThat(counted(KafkaErrorHandlerConfig.DEAD_LETTERS_REFUSED)).isEqualTo(1);
        assertThat(counted(KafkaErrorHandlerConfig.DEAD_LETTERS)).isZero();
    }

    @Test
    void theValueDeserializer_wrappedWhateverTheConfigurationNames() {
        var factory = new DefaultKafkaConsumerFactory<>(new HashMap<>(Map.<String, Object>of(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)));

        new KafkaErrorHandlerConfig().valueDeserializerWrapped().customize(factory);

        assertThat(factory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class)
                .containsEntry(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
    }

    @Test
    void anErrorHandlingDeserializerNamedAlready_byClassOrName_leftAsItIs() {
        for (Object named : new Object[] {ErrorHandlingDeserializer.class, ErrorHandlingDeserializer.class.getName()}) {
            var factory = new DefaultKafkaConsumerFactory<>(new HashMap<>(Map.<String, Object>of(
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, named,
                    ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class)));

            new KafkaErrorHandlerConfig().valueDeserializerWrapped().customize(factory);

            assertThat(factory.getConfigurationProperties())
                    .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, named)
                    .containsEntry(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
        }
    }

    @Test
    void theStoreAway_byItsExceptionsOrItsSqlState_waitedFor() {
        assertThat(KafkaErrorHandlerConfig.storeDown(new DataAccessResourceFailureException("connection refused"))).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(new CannotCreateTransactionException("no connection"))).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(new QueryTimeoutException("timed out"))).isTrue();
        // deep in a wrapper: a ListenerExecutionFailedException's cause's cause
        assertThat(KafkaErrorHandlerConfig.storeDown(new RuntimeException(new IllegalStateException(
                new SQLException("the database system is starting up", "57P03"))))).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(new SQLException("connection lost", "08006"))).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(new SQLException("too many clients", "53300"))).isTrue();
    }

    @Test
    void scyllaAway_byItsDriversExceptions_waitedFor() {
        Node node = mock(Node.class);
        assertThat(KafkaErrorHandlerConfig.storeDown(new NoNodeAvailableException())).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(new DriverTimeoutException("query timed out after PT2S"))).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(new ClosedConnectionException("lost"))).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(new RuntimeException("listener failed",
                new BusyConnectionException("too many requests in flight")))).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(new ConnectionInitException("refused", null))).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(
                new HeartbeatException(InetSocketAddress.createUnresolved("scylla", 9042), "no heartbeat", null))).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(new UnavailableException(node, ConsistencyLevel.ONE, 1, 0))).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(
                new ReadTimeoutException(node, ConsistencyLevel.ONE, 0, 1, false))).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(
                new WriteTimeoutException(node, ConsistencyLevel.ONE, 0, 1, WriteType.SIMPLE))).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(new OverloadedException(node))).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(new BootstrappingException(node))).isTrue();
    }

    @Test
    void redisAway_waitedFor() {
        // Redis (the user cache): Lettuce's connection failure - a DataAccessResourceFailureException's own kind
        assertThat(KafkaErrorHandlerConfig.storeDown(new RedisConnectionFailureException("Unable to connect"))).isTrue();
    }

    @Test
    void aDriverFailureNoWaitCures_notTheStoreAway() {
        // a statement the server refuses, or a Java type the driver has no codec for: the same on every try - never an
        // unbounded wait (a code bug would hold the listener for ever)
        Node node = mock(Node.class);
        assertThat(KafkaErrorHandlerConfig.storeDown(new InvalidQueryException(node, "unconfigured table"))).isFalse();
        assertThat(KafkaErrorHandlerConfig.storeDown(new SyntaxError(node, "line 1:0 no viable alternative"))).isFalse();
        assertThat(KafkaErrorHandlerConfig.storeDown(
                new CodecNotFoundException(DataTypes.TEXT, GenericType.UUID))).isFalse();
    }

    @Test
    void aFailureNoWaitCures_notTheStoreAway() {
        assertThat(KafkaErrorHandlerConfig.storeDown(new IllegalStateException("a bug"))).isFalse();
        assertThat(KafkaErrorHandlerConfig.storeDown(new DataIntegrityViolationException("uq_users_email"))).isFalse();
        assertThat(KafkaErrorHandlerConfig.storeDown(new SQLException("syntax error", "42601"))).isFalse();
        assertThat(KafkaErrorHandlerConfig.storeDown(new SQLException("no state"))).isFalse();
    }

    @Test
    void theDeadLettersHeaders_text_oneLine() {
        var wrapped = new RuntimeException("Listener failed",
                new IllegalStateException("duplicate key\n  Detail:\tKey (email)=(a@b) exists\u001e"));

        Headers headers = KafkaErrorHandlerConfig.textHeaders(rec, wrapped);

        assertThat(new String(headers.lastHeader(KafkaErrorHandlerConfig.ORIGINAL_PARTITION).value(), UTF_8)).isEqualTo("2");
        assertThat(new String(headers.lastHeader(KafkaErrorHandlerConfig.ORIGINAL_OFFSET).value(), UTF_8)).isEqualTo("7");
        assertThat(new String(headers.lastHeader(KafkaErrorHandlerConfig.ORIGINAL_TIMESTAMP).value(), UTF_8))
                .isEqualTo(String.valueOf(rec.timestamp()));
        assertThat(new String(headers.lastHeader(KafkaErrorHandlerConfig.EXCEPTION_MESSAGE).value(), UTF_8))
                .as("the cause's message, its control characters and space runs one space")
                .isEqualTo("duplicate key Detail: Key (email)=(a@b) exists");
    }

    @Test
    void aLongMessage_cutToAThousandCharacters() {
        assertThat(KafkaErrorHandlerConfig.oneLine("x".repeat(5000))).hasSize(1000);
    }

    private double counted(String name) {
        Counter counter = meters.find(name).tag("topic", "user.events").counter();
        return counter == null ? 0 : counter.count();
    }
}
