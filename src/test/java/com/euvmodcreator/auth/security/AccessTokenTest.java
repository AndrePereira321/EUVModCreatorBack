package com.euvmodcreator.auth.security;

import com.euvmodcreator.IntegrationTest;
import com.euvmodcreator.auth.model.User;
import com.euvmodcreator.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Duration;

// Any path outside /api/auth needs a token. /api/nothing-here has no controller, so a request that gets past
// security ends in a 404 — which proves the token was accepted.
class AccessTokenTest extends IntegrationTest {

    private static final String PROTECTED_PATH = "/api/nothing-here";

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void requestWithoutTokenIsUnauthorized() {
        client.get().uri(PROTECTED_PATH)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void tokenIssuedByTheAppIsAccepted() {
        String token = tokenService.issueAccessToken(savedUser());

        client.get().uri(PROTECTED_PATH)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("not_found");
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        TokenService forger = new TokenService(
                new AuthProperties("unused", Duration.ofMinutes(15), Duration.ofDays(30)),
                NimbusJwtEncoder.withSecretKey(new SecretKeySpec(otherKey, "HmacSHA256")).build());

        client.get().uri(PROTECTED_PATH)
                .header("Authorization", "Bearer " + forger.issueAccessToken(savedUser()))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private User savedUser() {
        User user = new User();
        user.setUsername("andre");
        return userRepository.save(user);
    }

}
