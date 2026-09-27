package com.euvmodcreator.auth.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Base64;

@Validated
@ConfigurationProperties("euv-app.auth.jwt")
record AuthProperties(
        @NotBlank
        String secret,

        @DefaultValue("15m")
        Duration accessTokenTtl,

        @DefaultValue("30d")
        Duration refreshTokenTtl
) {

    private static final int MIN_SECRET_BYTES = 32;

    AuthProperties {
        if (secret != null && !secret.isBlank() && decodedLength(secret) < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("euv-app.auth.jwt.secret must be Base64 of at least " + MIN_SECRET_BYTES
                    + " random bytes: openssl rand -base64 32");
        }
    }

    private static int decodedLength(String secret) {
        try {
            return Base64.getDecoder().decode(secret).length;
        } catch (IllegalArgumentException notBase64) {
            return 0;
        }
    }
}
