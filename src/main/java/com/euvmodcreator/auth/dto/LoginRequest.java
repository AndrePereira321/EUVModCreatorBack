package com.euvmodcreator.auth.dto;

import com.euvmodcreator.validation.MaxBytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank
        @Size(max = 32)
        String username,

        @NotBlank
        @MaxBytes(72)
        String password
) {

    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=***]";
    }

}

