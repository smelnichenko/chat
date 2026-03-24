package io.schnappy.chat.dto;

import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.UUID;

@Value
@Builder
@JsonDeserialize(builder = ChannelKeyBundleDto.ChannelKeyBundleDtoBuilder.class)
public class ChannelKeyBundleDto {

    @JsonPOJOBuilder(withPrefix = "")
    public static class ChannelKeyBundleDtoBuilder {}
    UUID userUuid;
    int keyVersion;
    String encryptedChannelKey;
    String wrapperPublicKey;
}
