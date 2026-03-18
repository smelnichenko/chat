package io.schnappy.chat.dto;

import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

@Value
@Builder
@JsonDeserialize(builder = ChannelKeyBundleDto.ChannelKeyBundleDtoBuilder.class)
public class ChannelKeyBundleDto {

    @JsonPOJOBuilder(withPrefix = "")
    public static class ChannelKeyBundleDtoBuilder {}
    Long userId;
    int keyVersion;
    String encryptedChannelKey;
    String wrapperPublicKey;
}
