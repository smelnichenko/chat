package io.schnappy.chat.kafka;

/**
 * A Kafka record no retry can store - a chat message replying to a parent id that is not one. Never retried: the record
 * goes to its topic's .DLT at once, kept for a look (KafkaErrorHandlerConfig).
 */
public class InvalidChatMessageException extends RuntimeException {
    public InvalidChatMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
