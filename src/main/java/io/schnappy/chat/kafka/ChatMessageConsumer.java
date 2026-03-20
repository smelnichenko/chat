package io.schnappy.chat.kafka;

import io.schnappy.chat.dto.ChatMessageDto;
import io.schnappy.chat.repository.ScyllaMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageConsumer {

    private final ScyllaMessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(topics = "chat.messages", groupId = "chat-persistence")
    public void persistMessage(ChatMessageDto message) {
        try {
            UUID parentId = message.getParentMessageId() != null
                ? UUID.fromString(message.getParentMessageId()) : null;
            messageRepository.saveMessage(message, parentId);
        } catch (RuntimeException e) {
            log.error("Failed to persist message: {}", e.getMessage());
            throw e;
        }
    }

    @KafkaListener(topics = "chat.messages", groupId = "chat-delivery")
    public void deliverMessage(ChatMessageDto message) {
        messagingTemplate.convertAndSend(
            "/topic/channel." + message.getChannelId(),
            message
        );
    }
}
