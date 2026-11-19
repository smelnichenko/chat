package io.schnappy.chat.kafka;

/** chat.messages did not take a message: the sender is told (503), never shown a message the history will not have. */
public class MessageNotSentException extends RuntimeException {
    public MessageNotSentException(String messageId, Throwable cause) {
        super("chat message " + messageId + " not sent: " + (cause == null ? "no cause" : cause.getMessage()), cause);
    }
}
