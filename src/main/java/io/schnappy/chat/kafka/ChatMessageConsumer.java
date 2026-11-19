package io.schnappy.chat.kafka;

import io.schnappy.chat.dto.ChatMessageDto;
import io.schnappy.chat.repository.ScyllaMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ChatMessageConsumer {

    /** A message's time at most this far ahead of the consumer's clock (producer clocks drift, never by hours). */
    static final Duration MAX_AHEAD = Duration.ofHours(1);
    /** Before any chat message: a time earlier is no message's (an epoch zero, a unit wrong). */
    static final Instant EARLIEST = Instant.parse("2020-01-01T00:00:00Z");

    private final ScyllaMessageRepository messageRepository;

    /**
     * The message into the history. A failure propagates to KafkaErrorHandlerConfig's handler, which logs each try and
     * retries the record - ScyllaDB restarting costs it nothing; a reply to a parent id that is not one is kept on
     * chat.messages.DLT at once. Three consumers: a record the handler holds stalls the channels of its partitions
     * (a third of them), not every channel - a channel is one partition (the key), so its order and chain hold.
     */
    @KafkaListener(topics = "chat.messages", groupId = "chat-persistence", concurrency = "3")
    public void persistMessage(ChatMessageDto message) {
        validate(message, Instant.now());
        messageRepository.saveMessage(message, parent(message));
    }

    /**
     * What the history's row is made from (ScyllaMessageRepository.rowId): the producer's message id, a uuid, and its
     * time. Without them a retry cannot write the row it wrote before; a time far ahead would become the floor of every
     * later message's id in the channel (a row sorts after the chain head) - frozen there, out of today's history. No
     * retry cures either: chat.messages.DLT at once. Accepted once, a message is accepted on every later try (a later
     * now): a half write is never left unfinished by the check.
     */
    static void validate(ChatMessageDto message, Instant now) {
        String id = message.getMessageId();
        try {
            UUID.fromString(id == null ? "" : id);
        } catch (IllegalArgumentException e) {
            throw new InvalidChatMessageException("a message whose id is not one: " + id, e);
        }
        Instant at = message.getCreatedAt();
        if (at == null) {
            throw new InvalidChatMessageException("a message without its time: " + id);
        }
        if (at.isBefore(EARLIEST) || at.isAfter(now.plus(MAX_AHEAD))) {
            throw new InvalidChatMessageException("a message's time out of bounds: " + at + " (" + id + ")");
        }
    }

    /** The message's parent when it is a reply (a blank id: none) - a parent id that is not a uuid no retry can store. */
    private static UUID parent(ChatMessageDto message) {
        String id = message.getParentMessageId();
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new InvalidChatMessageException("a reply to a parent id that is not one: " + id, e);
        }
    }
}
