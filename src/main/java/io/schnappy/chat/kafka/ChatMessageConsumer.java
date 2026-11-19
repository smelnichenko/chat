package io.schnappy.chat.kafka;

import io.schnappy.chat.dto.ChatMessageDto;
import io.schnappy.chat.repository.ScyllaMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ChatMessageConsumer {

    private final ScyllaMessageRepository messageRepository;

    /**
     * The message into the history. A failure propagates to KafkaErrorHandlerConfig's handler, which logs each try and
     * retries the record - ScyllaDB restarting costs it nothing; a reply to a parent id that is not one is kept on
     * chat.messages.DLT at once. Three consumers: a record the handler holds stalls the channels of its partitions
     * (a third of them), not every channel - a channel is one partition (the key), so its order and chain hold.
     */
    @KafkaListener(topics = "chat.messages", groupId = "chat-persistence", concurrency = "3")
    public void persistMessage(ChatMessageDto message) {
        messageRepository.saveMessage(message, parent(message));
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
