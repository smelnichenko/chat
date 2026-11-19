package io.schnappy.chat.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;

/**
 * How a Kafka record whose listener failed is retried. The retry holds the listener (the next record waits): 1 s,
 * doubling, at most 30 s apart - for as long as its store is down (ScyllaDB, one node, restarting; CNPG's primary in place),
 * for 15 min of waits for any other failure, then its topic's .DLT.
 */
@ConfigurationProperties(prefix = "chat.kafka.retry")
public record KafkaRetryProperties(
    @DefaultValue("1s") Duration initialInterval,
    @DefaultValue("2") double multiplier,
    @DefaultValue("30s") Duration maxInterval,
    @DefaultValue("15m") Duration maxElapsed
) {
    /** The back-off of a failure no store restart explains: maxElapsed of waits, then the record's .DLT. */
    public ExponentialBackOff backOff() {
        var backOff = unboundedBackOff();
        backOff.setMaxElapsedTime(maxElapsed.toMillis());
        return backOff;
    }

    /**
     * The back-off of a store that is down (its connection refused, it starting up): the same waits, no end. The
     * record waits for its store however long it is away - never on the .DLT, where it would come back out of
     * order; the KafkaListenerFailing alert says the listener is held.
     */
    public ExponentialBackOff unboundedBackOff() {
        var backOff = new ExponentialBackOff(initialInterval.toMillis(), multiplier);
        backOff.setMaxInterval(maxInterval.toMillis());
        return backOff;
    }
}
