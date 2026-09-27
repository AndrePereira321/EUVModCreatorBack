package com.euvmodcreator.auth.security;

import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("euv-app.auth.rate-limit")
public record RateLimitProperties(

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
        Duration failedLoginLockDuration,

        @Positive
        @DefaultValue(DEFAULT_LOGIN_CONCURRENCY)
        int loginConcurrency,

        @Positive
        @DefaultValue(DEFAULT_REGISTER_CONCURRENCY)
        int registerConcurrency

) {

    static final String DEFAULT_LOGIN_CONCURRENCY = "16";

    static final String DEFAULT_REGISTER_CONCURRENCY = "2";

    public static final String LOGIN_CONCURRENCY_LIMIT =
            "${euv-app.auth.rate-limit.login-concurrency:" + DEFAULT_LOGIN_CONCURRENCY + "}";

    public static final String REGISTER_CONCURRENCY_LIMIT =
            "${euv-app.auth.rate-limit.register-concurrency:" + DEFAULT_REGISTER_CONCURRENCY + "}";

}
