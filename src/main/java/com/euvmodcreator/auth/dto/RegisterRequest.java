package com.euvmodcreator.auth.dto;

import com.euvmodcreator.validation.MaxBytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 32)
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "may only contain letters, digits and underscores")
        String username,

        @NotBlank
        @Size(min = 8, max = 32)
        @MaxBytes(72) // BCrypt's input limit
        String password
) {

    @Override
    public String toString() {
        return "RegisterRequest[username=" + username + ", password=***]";
    }

}
