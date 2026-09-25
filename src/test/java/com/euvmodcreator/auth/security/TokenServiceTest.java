package com.euvmodcreator.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {

    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);

    private static final Duration REFRESH_TTL = Duration.ofDays(30);

    private final SecretKey key = randomKey();

    private final TokenService tokenService = new TokenService(
            new AuthProperties("unused", ACCESS_TTL, REFRESH_TTL), NimbusJwtEncoder.withSecretKey(key).build());

    private final JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();

    @Test
    void issuesTokenWhoseSubjectIsTheUserId() {
        UUID id = UUID.randomUUID();

        Jwt jwt = decoder.decode(tokenService.issueAccessToken(id));

        assertThat(jwt.getSubject()).isEqualTo(id.toString());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(ACCESS_TTL);
    }

    @Test
    void refusesMissingUserId() {
        assertThatThrownBy(() -> tokenService.issueAccessToken(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 32 random bytes in URL-safe Base64 without padding: nothing a cookie parser could mangle.
    @Test
    void refreshTokenIs32RandomBytesInUrlSafeBase64() {
        String first = tokenService.newRefreshToken().value();
        String second = tokenService.newRefreshToken().value();

        assertThat(first).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(first)).hasSize(32);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void refreshTokenExpiresAfterTheConfiguredTtl() {
        Instant before = Instant.now();
        RefreshToken token = tokenService.newRefreshToken();
        Instant after = Instant.now();

        assertThat(token.expiresAt()).isBetween(before.plus(REFRESH_TTL), after.plus(REFRESH_TTL));
    }

    // Refresh passes the session's expiry in, so rotating the token never extends the session.
    @Test
    void refreshTokenCanKeepAnExistingExpiry() {
        Instant sessionExpiry = Instant.parse("2026-10-25T12:00:00Z");

        RefreshToken token = tokenService.newRefreshToken(sessionExpiry);

        assertThat(token.expiresAt()).isEqualTo(sessionExpiry);
        assertThat(token.value()).matches("[A-Za-z0-9_-]{43}");
    }

    // The published SHA-256 test vector: catches hex-encoding the input instead of hashing it, or Base64 output.
    @Test
    void hashesRefreshTokenWithSha256AsHex() {
        assertThat(tokenService.hashRefreshToken("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    private static SecretKey randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

}
