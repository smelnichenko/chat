package io.schnappy.chat.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UploadKeysRequest(
    @NotBlank @Size(max = 4096) String publicKey,
    @NotBlank @Size(max = 8192) String encryptedPrivateKey,
    @NotBlank @Size(min = 24, max = 256) String pbkdf2Salt,
    @NotNull @Min(600000) Integer pbkdf2Iterations
) {}
