package com.euvmodcreator.auth.security;

import java.time.Instant;

public record RefreshToken(
        String value,
        Instant expiresAt
) {

    @Override
    public String toString() {
        return "RefreshToken[value=***, expiresAt=" + expiresAt + "]";
    }

}
