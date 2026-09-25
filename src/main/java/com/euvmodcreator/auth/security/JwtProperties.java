package com.euvmodcreator.auth.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("app.jwt")
record JwtProperties(
        @NotBlank
        String secret,

        @DefaultValue("15m")
        Duration accessTokenTtl
) {}
