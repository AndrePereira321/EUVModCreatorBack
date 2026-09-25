package com.euvmodcreator.auth.dto;

import com.euvmodcreator.validation.MaxBytes;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank
        String username,

        @NotBlank
        @MaxBytes(72) // BCrypt's input limit
        String password
) {

    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=***]";
    }

}

