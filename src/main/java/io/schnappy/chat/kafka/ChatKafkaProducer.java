package io.schnappy.chat.kafka;

import io.schnappy.chat.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatKafkaProducer {

    private static final String TOPIC = "chat.messages";
    private final KafkaTemplate<String, ChatMessageDto> kafkaTemplate;

    public void sendMessage(ChatMessageDto message) {
        String key = String.valueOf(message.getChannelId());
        kafkaTemplate.send(TOPIC, key, message)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to send message to Kafka: {}", ex.getMessage());
                }
            });
    }
}
