package com.euvmodcreator.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// Binds the properties the way the app does at startup, without starting the app.
class RateLimitPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaultsLiveInCode() {
        contextRunner.run(context -> assertThat(context.getBean(RateLimitProperties.class)).isEqualTo(
                new RateLimitProperties(
                        10, Duration.ofMinutes(1), 5, Duration.ofHours(1), 5, Duration.ofMinutes(5), 16, 2)));
    }

    @Test
    void zeroCapacityStopsStartup() {
        contextRunner.withPropertyValues("euv-app.auth.rate-limit.login-capacity=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("loginCapacity"));
    }

    // A zero lock duration would switch the lockout off without a word.
    @Test
    void zeroDurationStopsStartup() {
        contextRunner.withPropertyValues("euv-app.auth.rate-limit.failed-login-lock-duration=0s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("failedLoginLockDuration"));
    }

    // @ConcurrencyLimit resolves its placeholder on the first call, so without this binding a zero (every login
    // refused) or a word (a 500) would first show on the first login.
    @Test
    void zeroConcurrencyStopsStartup() {
        contextRunner.withPropertyValues("euv-app.auth.rate-limit.login-concurrency=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("loginConcurrency"));
    }

    @Test
    void nonNumericConcurrencyStopsStartup() {
        contextRunner.withPropertyValues("euv-app.auth.rate-limit.register-concurrency=many")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("register-concurrency"));
    }

    // The annotations read the placeholders, not the bean, so key and default must agree with the binding.
    @Test
    void concurrencyPlaceholdersResolveToTheBoundValues() {
        contextRunner.withPropertyValues("euv-app.auth.rate-limit.login-concurrency=7")
                .run(context -> {
                    RateLimitProperties properties = context.getBean(RateLimitProperties.class);
                    Environment environment = context.getEnvironment();

                    assertThat(environment.resolvePlaceholders(RateLimitProperties.LOGIN_CONCURRENCY_LIMIT))
                            .isEqualTo(String.valueOf(properties.loginConcurrency()))
                            .isEqualTo("7");
                    assertThat(environment.resolvePlaceholders(RateLimitProperties.REGISTER_CONCURRENCY_LIMIT))
                            .isEqualTo(String.valueOf(properties.registerConcurrency()))
                            .isEqualTo("2");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class Config {
    }

}
