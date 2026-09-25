package com.euvmodcreator.auth.security;

import com.euvmodcreator.auth.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    private final SecretKey key = randomKey();

    private final TokenService tokenService = new TokenService(
            new JwtProperties("unused", TTL), NimbusJwtEncoder.withSecretKey(key).build());

    private final JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();

    @Test
    void issuesTokenWhoseSubjectIsTheUserId() {
        UUID id = UUID.randomUUID();

        Jwt jwt = decoder.decode(tokenService.issueAccessToken(userWithId(id)));

        assertThat(jwt.getSubject()).isEqualTo(id.toString());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(TTL);
    }

    @Test
    void refusesUserThatWasNeverSaved() {
        assertThatThrownBy(() -> tokenService.issueAccessToken(new User()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static User userWithId(UUID id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static SecretKey randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

}
