package io.schnappy.chat.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaRetryPropertiesTest {

    @Test
    void defaults_oneSecondDoublingToThirty_fifteenMinutesInAll() {
        KafkaRetryProperties retry = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("chat.kafka.retry", KafkaRetryProperties.class);

        ExponentialBackOff backOff = retry.backOff();

        assertThat(backOff.getInitialInterval()).isEqualTo(1_000);
        assertThat(backOff.getMultiplier()).isEqualTo(2.0);
        assertThat(backOff.getMaxInterval()).isEqualTo(30_000);
        assertThat(backOff.getMaxElapsedTime()).isEqualTo(900_000);
    }

    @Test
    void bound_fromItsProperties() {
        KafkaRetryProperties retry = new Binder(new MapConfigurationPropertySource(Map.of(
                "chat.kafka.retry.initial-interval", "100ms",
                "chat.kafka.retry.multiplier", "3",
                "chat.kafka.retry.max-interval", "500ms",
                "chat.kafka.retry.max-elapsed", "2s")))
                .bindOrCreate("chat.kafka.retry", KafkaRetryProperties.class);

        ExponentialBackOff backOff = retry.backOff();

        assertThat(backOff.getInitialInterval()).isEqualTo(100);
        assertThat(backOff.getMultiplier()).isEqualTo(3.0);
        assertThat(backOff.getMaxInterval()).isEqualTo(500);
        assertThat(backOff.getMaxElapsedTime()).isEqualTo(2_000);
    }

    @Test
    void theDefaultWaits_doubleToTheCap_andStopAfterAboutFifteenMinutes() {
        BackOffExecution execution = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("chat.kafka.retry", KafkaRetryProperties.class).backOff().start();

        List<Long> waits = new ArrayList<>();
        for (long w = execution.nextBackOff(); w != BackOffExecution.STOP && waits.size() < 1_000; w = execution.nextBackOff()) {
            waits.add(w);
        }

        assertThat(waits)
                .startsWith(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
                .allMatch(w -> w <= 30_000L);
        // a store restart (CNPG's primary: a minute or two) is well inside; the record is not held for ever
        assertThat(waits.stream().mapToLong(Long::longValue).sum()).isBetween(870_000L, 930_000L);
    }
}
