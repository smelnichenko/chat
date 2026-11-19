package io.schnappy.chat.kafka;

import io.schnappy.chat.dto.ChatMessageDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class ChatKafkaProducer {

    private static final String TOPIC = "chat.messages";
    /**
     * Longer than the producer's own delivery timeout (application.yml: 15 s), so the send has come to its end - taken
     * or refused - before the caller is answered: a timeout here and a late delivery after it would be a message both
     * failed and stored.
     */
    static final Duration SEND_WAIT = Duration.ofSeconds(20);

    private final KafkaTemplate<String, ChatMessageDto> kafkaTemplate;
    private final Duration sendWait;

    @Autowired
    public ChatKafkaProducer(KafkaTemplate<String, ChatMessageDto> kafkaTemplate) {
        this(kafkaTemplate, SEND_WAIT);
    }

    ChatKafkaProducer(KafkaTemplate<String, ChatMessageDto> kafkaTemplate, Duration sendWait) {
        this.kafkaTemplate = kafkaTemplate;
        this.sendWait = sendWait;
    }

    /**
     * The message onto chat.messages, waited for: the broker took it, or the caller fails. Not waited for, a send the
     * broker refused (or never took) was logged and lost while the request answered 201 - the message missing from the
     * history, its sender none the wiser; a system notice posted from a listener was committed past. Now the request
     * answers 503 (GlobalExceptionHandler) and a listener's record is retried (KafkaErrorHandlerConfig).
     */
    public void sendMessage(ChatMessageDto message) {
        String key = String.valueOf(message.getChannelId());
        try {
            kafkaTemplate.send(TOPIC, key, message).get(sendWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessageNotSentException(message.getMessageId(), e);
        } catch (ExecutionException e) {
            throw new MessageNotSentException(message.getMessageId(), e.getCause());
        } catch (TimeoutException e) {
            throw new MessageNotSentException(message.getMessageId(), e);
        }
    }
}
