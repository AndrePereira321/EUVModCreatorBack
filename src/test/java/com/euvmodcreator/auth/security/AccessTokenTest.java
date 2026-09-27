package com.euvmodcreator.auth.security;

import com.euvmodcreator.IntegrationTest;
import com.euvmodcreator.auth.dto.LoginRequest;
import com.euvmodcreator.auth.dto.RegisterRequest;
import com.euvmodcreator.auth.entity.User;
import com.euvmodcreator.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
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
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Any path outside /api/auth needs a token. /api/nothing-here has no controller, so a request that gets past
// security ends in a 404 — which proves the token was accepted.
class AccessTokenTest extends IntegrationTest {

    private static final String PROTECTED_PATH = "/api/nothing-here";

    private static final String COOKIE = "refresh_token";

    private static final Instant SESSION_END = Instant.now().plus(Duration.ofDays(30));

    @Autowired
    private TokenService tokenService;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private UserRepository userRepository;

    @Test
    void tokenIssuedByTheAppIsAccepted() {
        String token = tokenService.issueAccessToken(savedUser().getId(), UUID.randomUUID(), SESSION_END);

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
                new AuthProperties(
                        Base64.getEncoder().encodeToString(otherKey), Duration.ofMinutes(15), Duration.ofDays(30)),
                NimbusJwtEncoder.withSecretKey(new SecretKeySpec(otherKey, "HmacSHA256")).build());

        expectInvalidAccessToken(request(forger.issueAccessToken(savedUser().getId(), UUID.randomUUID(), SESSION_END)));
    }

    // One second past exp: the decoder allows no clock skew, so a token stops working the moment its session does.
    @Test
    void expiredTokenIsInvalidAccessToken() {
        expectInvalidAccessToken(request(expiredToken()));
    }

    // Validly signed, but the converter can't tell which session it belongs to.
    @Test
    void tokenWithoutSessionIdIsInvalidAccessToken() {
        Instant now = Instant.now();
        JwtClaimsSet withoutSessionId = claims(now, now.plus(Duration.ofMinutes(15))).build();

        expectInvalidAccessToken(request(sign(withoutSessionId)));
    }

    // Validly signed, but UUID.fromString would throw, which escapes the filter as a 500 unless the converter wraps it.
    @Test
    void tokenWhoseSubjectIsNotAUuidIsInvalidAccessToken() {
        Instant now = Instant.now();
        JwtClaimsSet malformedSubject = claims(now, now.plus(Duration.ofMinutes(15)))
                .subject("not-a-uuid")
                .claim(TokenService.SESSION_ID_CLAIM, UUID.randomUUID().toString())
                .build();

        expectInvalidAccessToken(request(sign(malformedSubject)));
    }

    // The auth endpoints identify the caller by the cookie and ignore the Authorization header. The frontend sends
    // its access token with every request, and the one it holds when it calls refresh is exactly the expired one.
    @Test
    void expiredTokenDoesNotBlockRefresh() {
        String refreshToken = registerAndLogin();

        client.post().uri("/api/auth/refresh")
                .cookie(COOKIE, refreshToken)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken())
                .exchange()
                .expectStatus().isOk()
                .expectCookie().exists(COOKIE);
    }

    @Test
    void expiredTokenDoesNotBlockLogout() {
        String refreshToken = registerAndLogin();

        client.post().uri("/api/auth/logout")
                .cookie(COOKIE, refreshToken)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken())
                .exchange()
                .expectStatus().isNoContent()
                .expectCookie().maxAge(COOKIE, Duration.ZERO);
    }

    @Test
    void malformedTokenDoesNotBlockLogin() {
        register();

        client.post().uri("/api/auth/login")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt")
                .body(new LoginRequest("andre", "password123"))
                .exchange()
                .expectStatus().isOk();
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

    // Complete and validly signed; only exp is in the past.
    private String expiredToken() {
        Instant now = Instant.now();
        return sign(JwtClaimsSet.builder()
                .subject(UUID.randomUUID().toString())
                .claim(TokenService.SESSION_ID_CLAIM, UUID.randomUUID().toString())
                .issuedAt(now.minus(Duration.ofMinutes(15)))
                .expiresAt(now.minusSeconds(1))
                .build());
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

    private void register() {
        client.post().uri("/api/auth/register")
                .body(new RegisterRequest("andre", "password123"))
                .exchange()
                .expectStatus().isCreated();
    }

    private String registerAndLogin() {
        register();
        ResponseCookie cookie = client.post().uri("/api/auth/login")
                .body(new LoginRequest("andre", "password123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseCookies()
                .getFirst(COOKIE);

        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

}
