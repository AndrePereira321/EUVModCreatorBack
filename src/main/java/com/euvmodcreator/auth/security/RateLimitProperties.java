package com.euvmodcreator.auth.security;

import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("auth.rate-limit")
record RateLimitProperties(

        @Positive
        @DefaultValue("10")
        int loginCapacity,

        @DurationMin(seconds = 1)
        @DefaultValue("1m")
        Duration loginPeriod,

        @Positive
        @DefaultValue("5")
        int registerCapacity,

        @DurationMin(seconds = 1)
        @DefaultValue("1h")
        Duration registerPeriod,

        @Positive
        @DefaultValue("5")
        int maxFailedLogins,

        @DurationMin(seconds = 1)
        @DefaultValue("5m")
        Duration failedLoginLockDuration

) {
}
