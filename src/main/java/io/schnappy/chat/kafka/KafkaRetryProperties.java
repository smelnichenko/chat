package io.schnappy.chat.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;

/**
 * How long a Kafka record whose listener failed is retried before it goes to its topic's .DLT. The retry blocks its
 * partition (the next record waits), so a store restarting under it - CNPG restarts its primary in place, a minute or
 * two, ScyllaDB one node restarting - costs the record nothing: 1 s, doubling, at most 30 s apart, for 15 min of waits
 * in all.
 */
@ConfigurationProperties(prefix = "chat.kafka.retry")
public record KafkaRetryProperties(
    @DefaultValue("1s") Duration initialInterval,
    @DefaultValue("2") double multiplier,
    @DefaultValue("30s") Duration maxInterval,
    @DefaultValue("15m") Duration maxElapsed
) {
    public ExponentialBackOff backOff() {
        var backOff = new ExponentialBackOff(initialInterval.toMillis(), multiplier);
        backOff.setMaxInterval(maxInterval.toMillis());
        backOff.setMaxElapsedTime(maxElapsed.toMillis());
        return backOff;
    }
}
