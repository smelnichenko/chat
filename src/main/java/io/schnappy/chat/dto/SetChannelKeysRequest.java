package io.schnappy.chat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SetChannelKeysRequest(
    @NotEmpty @Size(max = 1000) List<@Valid MemberKeyBundle> bundles
) {
    public record MemberKeyBundle(
        @NotNull Long userId,
        @NotBlank @Size(max = 4096) String encryptedChannelKey,
        @NotBlank @Size(max = 4096) String wrapperPublicKey
    ) {}
}
