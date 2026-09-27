package com.euvmodcreator.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

// Binds the properties the way the app does at startup, without starting the app.
class AuthPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void acceptsSecretOf32Bytes() {
        contextRunner.withPropertyValues("euv-app.auth.jwt.secret=" + randomBase64(32))
                .run(context -> assertThat(context).hasNotFailed());
    }

    // Nimbus only checks the key length when it signs the first token, which would make every login a 500.
    @Test
    void secretShorterThan32BytesStopsStartup() {
        contextRunner.withPropertyValues("euv-app.auth.jwt.secret=" + randomBase64(16))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("at least 32"));
    }

    @Test
    void secretThatIsNotBase64StopsStartup() {
        contextRunner.withPropertyValues("euv-app.auth.jwt.secret=not base64, but long enough to hold 32 bytes")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("at least 32"));
    }

    @Test
    void missingSecretStopsStartup() {
        contextRunner.run(context -> assertThat(context).hasFailed());
    }

    private static String randomBase64(int bytes) {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class Config {
    }

}
