package io.schnappy.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDto {

    String messageId;
    long channelId;
    long userId;
    String username;
    String content;
    String parentMessageId;
    Instant createdAt;
    String hash;
    String prevHash;
    String editedContent;
    Integer keyVersion;
    String messageType;
    String metadata;
}
