package io.schnappy.chat.dto;

import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.time.Instant;

@Value
@Builder
@JsonDeserialize(builder = ChatMessageDto.ChatMessageDtoBuilder.class)
public class ChatMessageDto {

    @JsonPOJOBuilder(withPrefix = "")
    public static class ChatMessageDtoBuilder {}
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
