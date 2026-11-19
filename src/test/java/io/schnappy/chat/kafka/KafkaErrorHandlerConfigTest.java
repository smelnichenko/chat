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
import org.apache.kafka.common.header.Headers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.transaction.CannotCreateTransactionException;

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
        assertThat(KafkaErrorHandlerConfig.storeDown(new NoNodeAvailableException())).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(new DriverTimeoutException("query timed out after PT2S"))).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(new ClosedConnectionException("lost"))).isTrue();
        assertThat(KafkaErrorHandlerConfig.storeDown(new RuntimeException("listener failed",
                new BusyConnectionException("too many requests in flight")))).isTrue();
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
