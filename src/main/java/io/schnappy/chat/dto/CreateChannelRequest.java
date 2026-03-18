package io.schnappy.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateChannelRequest(
    @NotBlank @Size(max = 100) String name,
    Boolean encrypted
) {}
