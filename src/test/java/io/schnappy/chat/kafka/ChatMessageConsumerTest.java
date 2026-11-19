package io.schnappy.chat.kafka;

import io.schnappy.chat.dto.ChatMessageDto;
import io.schnappy.chat.repository.ScyllaMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatMessageConsumerTest {

    private static final UUID USER_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Mock
    private ScyllaMessageRepository messageRepository;

    @InjectMocks
    private ChatMessageConsumer chatMessageConsumer;

    // --- persistMessage ---

    @Test
    void persistMessage_savesMessageWithNullParent() {
        var message = ChatMessageDto.builder()
                .messageId(UUID.randomUUID().toString())
                .channelId(1L)
                .userUuid(USER_UUID)
                .username("alice")
                .content("Hello")
                .createdAt(Instant.now())
                .build();

        chatMessageConsumer.persistMessage(message);

        verify(messageRepository).saveMessage(eq(message), isNull());
    }

    @Test
    void persistMessage_aBlankParent_noParent() {
        var message = ChatMessageDto.builder()
                .messageId(UUID.randomUUID().toString())
                .channelId(1L)
                .userUuid(USER_UUID)
                .username("alice")
                .content("Not a reply")
                .parentMessageId(" ")
                .createdAt(Instant.now())
                .build();

        chatMessageConsumer.persistMessage(message);

        verify(messageRepository).saveMessage(eq(message), isNull());
    }

    @Test
    void validate_aMessageAsTheProducersSendIt_accepted() {
        Instant now = Instant.parse("2026-11-19T12:00:00Z");
        var message = ChatMessageDto.builder().messageId(UUID.randomUUID().toString()).createdAt(now.minusSeconds(30))
                .build();

        assertThatCode(() -> ChatMessageConsumer.validate(message, now)).doesNotThrowAnyException();
    }

    @Test
    void validate_noIdOrNotAUuid_refused() {
        Instant now = Instant.parse("2026-11-19T12:00:00Z");
        var noId = ChatMessageDto.builder().createdAt(now).build();
        var notAUuid = ChatMessageDto.builder().messageId("m-1").createdAt(now).build();

        assertThatThrownBy(() -> ChatMessageConsumer.validate(noId, now)).isInstanceOf(InvalidChatMessageException.class);
        assertThatThrownBy(() -> ChatMessageConsumer.validate(notAUuid, now))
                .isInstanceOf(InvalidChatMessageException.class).hasMessageContaining("m-1");
    }

    @Test
    void validate_noTime_refused() {
        var message = ChatMessageDto.builder().messageId(UUID.randomUUID().toString()).build();
        Instant now = Instant.now();

        assertThatThrownBy(() -> ChatMessageConsumer.validate(message, now))
                .isInstanceOf(InvalidChatMessageException.class).hasMessageContaining("without its time");
    }

    @Test
    void validate_aTimeFarAhead_orBeforeAnyMessage_refused() {
        // ahead, it would be the floor of every later message's row id in the channel - frozen, out of today's history
        Instant now = Instant.parse("2026-11-19T12:00:00Z");
        var ahead = ChatMessageDto.builder().messageId(UUID.randomUUID().toString())
                .createdAt(now.plus(ChatMessageConsumer.MAX_AHEAD).plusSeconds(1)).build();
        var early = ChatMessageDto.builder().messageId(UUID.randomUUID().toString()).createdAt(Instant.EPOCH).build();
        var justAhead = ChatMessageDto.builder().messageId(UUID.randomUUID().toString())
                .createdAt(now.plus(ChatMessageConsumer.MAX_AHEAD)).build();

        assertThatThrownBy(() -> ChatMessageConsumer.validate(ahead, now))
                .isInstanceOf(InvalidChatMessageException.class).hasMessageContaining("out of bounds");
        assertThatThrownBy(() -> ChatMessageConsumer.validate(early, now)).isInstanceOf(InvalidChatMessageException.class);
        assertThatCode(() -> ChatMessageConsumer.validate(justAhead, now)).doesNotThrowAnyException();
    }

    @Test
    void persistMessage_anInvalidMessage_nothingSaved() {
        var message = ChatMessageDto.builder().messageId("m-1").createdAt(Instant.now()).channelId(1L).build();

        assertThatThrownBy(() -> chatMessageConsumer.persistMessage(message))
                .isInstanceOf(InvalidChatMessageException.class);
        verify(messageRepository, never()).saveMessage(any(), any());
    }

    @Test
    void persistMessage_savesMessageWithParentId() {
        UUID parentId = UUID.randomUUID();
        var message = ChatMessageDto.builder()
                .messageId(UUID.randomUUID().toString())
                .channelId(1L)
                .userUuid(USER_UUID)
                .username("alice")
                .content("Reply")
                .parentMessageId(parentId.toString())
                .createdAt(Instant.now())
                .build();

        chatMessageConsumer.persistMessage(message);

        verify(messageRepository).saveMessage(message, parentId);
    }

    @Test
    void persistMessage_runtimeException_rethrows() {
        var message = ChatMessageDto.builder()
                .messageId(UUID.randomUUID().toString())
                .channelId(1L)
                .userUuid(USER_UUID)
                .username("alice")
                .content("Hello")
                .createdAt(Instant.now())
                .build();

        doThrow(new RuntimeException("ScyllaDB down")).when(messageRepository).saveMessage(any(), any());

        assertThatThrownBy(() -> chatMessageConsumer.persistMessage(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ScyllaDB down");
    }

    @Test
    void persistMessage_parentIdNotAUuid_throwsInvalid_andStoresNothing() {
        var message = ChatMessageDto.builder()
                .messageId(UUID.randomUUID().toString())
                .channelId(1L)
                .userUuid(USER_UUID)
                .username("alice")
                .content("Reply to nothing")
                .parentMessageId("not-a-message-id")
                .createdAt(Instant.now())
                .build();

        assertThatThrownBy(() -> chatMessageConsumer.persistMessage(message))
                .isInstanceOf(InvalidChatMessageException.class)
                .hasMessage("a reply to a parent id that is not one: not-a-message-id")
                .hasCauseInstanceOf(IllegalArgumentException.class);
        verify(messageRepository, never()).saveMessage(any(), any());
    }
}
