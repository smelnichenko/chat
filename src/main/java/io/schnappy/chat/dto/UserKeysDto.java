package io.schnappy.chat.dto;

import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

@Value
@Builder
@JsonDeserialize(builder = UserKeysDto.UserKeysDtoBuilder.class)
public class UserKeysDto {

    @JsonPOJOBuilder(withPrefix = "")
    public static class UserKeysDtoBuilder {}
    String publicKey;
    String encryptedPrivateKey;
    String pbkdf2Salt;
    int pbkdf2Iterations;
    int keyVersion;
}
