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

    private String messageId;
    private long channelId;
    private long userId;
    private String username;
    private String content;
    private String parentMessageId;
    private Instant createdAt;
    private String hash;
    private String prevHash;
    private String editedContent;
    private Integer keyVersion;
    private String messageType;
    private String metadata;
}
