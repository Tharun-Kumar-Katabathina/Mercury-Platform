package com.mercury.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        // bcrypt only uses the first 72 bytes, so a longer password would be silently truncated: refuse it instead
        @NotBlank @Size(min = 10, max = 72, message = "must be 10 to 72 characters") String password) {
}
