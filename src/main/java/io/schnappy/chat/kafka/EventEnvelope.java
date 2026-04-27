package io.schnappy.chat.kafka;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope(
    UUID id,
    String type,
    int version,
    Instant ts,
    String service,
    String subject,
    String actor,
    Object payload
) {

    public static EventEnvelope of(String type, String subject, String actor, Object payload) {
        return new EventEnvelope(
            UUID.randomUUID(),
            type,
            1,
            Instant.now(),
            "chat",
            subject,
            actor,
            payload
        );
    }
}
