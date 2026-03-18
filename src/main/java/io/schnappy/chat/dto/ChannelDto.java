package io.schnappy.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

@Value
@Builder
@JsonDeserialize(builder = ChannelDto.ChannelDtoBuilder.class)
public class ChannelDto {

    @JsonPOJOBuilder(withPrefix = "")
    public static class ChannelDtoBuilder {}
    Long id;
    String name;
    String createdAt;
    int memberCount;
    boolean joined;
    @JsonProperty("isOwner")
    boolean isOwner;
    int unreadCount;
    boolean encrypted;
    int currentKeyVersion;
    @JsonProperty("isSystem")
    boolean isSystem;
}
