package com.euvmodcreator.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// Binds the properties the way the app does at startup, without starting the app.
class RateLimitPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaultsLiveInCode() {
        contextRunner.run(context -> assertThat(context.getBean(RateLimitProperties.class)).isEqualTo(
                new RateLimitProperties(10, Duration.ofMinutes(1), 5, Duration.ofHours(1), 5, Duration.ofMinutes(5))));
    }

    @Test
    void zeroCapacityStopsStartup() {
        contextRunner.withPropertyValues("auth.rate-limit.login-capacity=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("loginCapacity"));
    }

    // A zero lock duration would switch the lockout off without a word.
    @Test
    void zeroDurationStopsStartup() {
        contextRunner.withPropertyValues("auth.rate-limit.failed-login-lock-duration=0s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("failedLoginLockDuration"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class Config {
    }

}
