package com.euvmodcreator.auth.security;

import com.euvmodcreator.IntegrationTest;
import com.euvmodcreator.auth.entity.User;
import com.euvmodcreator.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.client.RestTestClient;

import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

// Any path outside /api/auth needs a token. /api/nothing-here has no controller, so a request that gets past
// security ends in a 404 — which proves the token was accepted.
class AccessTokenTest extends IntegrationTest {

    private static final String PROTECTED_PATH = "/api/nothing-here";

    @Autowired
    private TokenService tokenService;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private UserRepository userRepository;

    @Test
    void tokenIssuedByTheAppIsAccepted() {
        String token = tokenService.issueAccessToken(savedUser().getId(), UUID.randomUUID());

        request(token)
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("not_found");
    }

    @Test
    void requestWithoutTokenIsInvalidAccessToken() {
        expectInvalidAccessToken(client.get().uri(PROTECTED_PATH).exchange());
    }

    @Test
    void malformedTokenIsInvalidAccessToken() {
        expectInvalidAccessToken(request("not.a.jwt"));
    }

    @Test
    void tokenSignedWithAnotherKeyIsInvalidAccessToken() {
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        TokenService forger = new TokenService(
                new AuthProperties("unused", Duration.ofMinutes(15), Duration.ofDays(30)),
                NimbusJwtEncoder.withSecretKey(new SecretKeySpec(otherKey, "HmacSHA256")).build());

        expectInvalidAccessToken(request(forger.issueAccessToken(savedUser().getId(), UUID.randomUUID())));
    }

    // Five minutes past exp: the decoder allows 60 seconds of clock skew.
    @Test
    void expiredTokenIsInvalidAccessToken() {
        Instant now = Instant.now();
        JwtClaimsSet expired = claims(now.minus(Duration.ofMinutes(20)), now.minus(Duration.ofMinutes(5)))
                .claim(TokenService.SESSION_ID_CLAIM, UUID.randomUUID().toString())
                .build();

        expectInvalidAccessToken(request(sign(expired)));
    }

    // Validly signed, but the converter can't tell which session it belongs to.
    @Test
    void tokenWithoutSessionIdIsInvalidAccessToken() {
        Instant now = Instant.now();
        JwtClaimsSet withoutSessionId = claims(now, now.plus(Duration.ofMinutes(15))).build();

        expectInvalidAccessToken(request(sign(withoutSessionId)));
    }

    private RestTestClient.ResponseSpec request(String token) {
        return client.get().uri(PROTECTED_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    // Problem Details like every other error, written by AccessTokenEntryPoint rather than GlobalExceptionHandler.
    private static void expectInvalidAccessToken(RestTestClient.ResponseSpec response) {
        response.expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("auth.invalid_access_token");
    }

    private JwtClaimsSet.Builder claims(Instant issuedAt, Instant expiresAt) {
        return JwtClaimsSet.builder()
                .subject(savedUser().getId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt);
    }

    private String sign(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private User savedUser() {
        User user = new User();
        user.setUsername("andre");
        return userRepository.save(user);
    }

}
