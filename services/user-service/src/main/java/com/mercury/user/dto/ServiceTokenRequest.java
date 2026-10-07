package com.mercury.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ServiceTokenRequest(
        @NotBlank @Size(max = 64) String clientId,
        @NotBlank @Size(max = 200) String clientSecret) {
}
