package com.euvmodcreator.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 32)
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "may only contain letters, digits and underscores")
        String username,

        @NotBlank
        @Size(min = 8, max = 32)
        String password
) {

    private static final int BCRYPT_MAX_BYTES = 72;

    // BCrypt rejects passwords over 72 bytes, and @Size counts characters: "é" is one character but two bytes.
    @AssertTrue(message = "password is too long")
    boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
    }

    @Override
    public String toString() {
        return "RegisterRequest[username=" + username + ", password=***]";
    }

}
