package com.euvmodcreator.auth;

import com.euvmodcreator.IntegrationTest;
import com.euvmodcreator.auth.dto.LoginRequest;
import com.euvmodcreator.auth.dto.LoginResponse;
import com.euvmodcreator.auth.dto.RegisterRequest;
import com.euvmodcreator.auth.dto.RegisterResponse;
import com.euvmodcreator.auth.model.UserSession;
import com.euvmodcreator.auth.repository.UserSessionRepository;
import com.euvmodcreator.auth.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshEndpointTest extends IntegrationTest {

    private static final String COOKIE = "refresh_token";

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void returnsAccessTokenForTheSessionUser() {
        UUID id = register();

        LoginResponse response = refresh(login())
                .expectStatus().isOk()
                .expectBody(LoginResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(jwtDecoder.decode(response.accessToken()).getSubject()).isEqualTo(id.toString());
    }

    @Test
    void setsNewCookieWithTheSameAttributesAsLogin() {
        register();
        String oldToken = login();

        String newToken = refreshToken(refresh(oldToken)
                .expectStatus().isOk()
                .expectCookie().httpOnly(COOKIE, true)
                .expectCookie().secure(COOKIE, true)
                .expectCookie().sameSite(COOKIE, "Strict")
                .expectCookie().path(COOKIE, "/api/auth")
                .expectBody()
                .returnResult());

        assertThat(newToken).isNotEqualTo(oldToken);
    }

    @Test
    void oldTokenStopsWorkingAndTheNewOneWorks() {
        register();
        String oldToken = login();
        String newToken = successfulRefresh(oldToken);

        expectInvalidRefreshToken(refresh(oldToken));
        refresh(newToken).expectStatus().isOk();
    }

    @Test
    void rotatesTheTokenOnTheSameSessionWithoutExtendingIt() {
        register();
        String oldToken = login();
        UserSession before = userSessionRepository.findAll().getFirst();

        String newToken = successfulRefresh(oldToken);

        assertThat(userSessionRepository.findAll()).singleElement().satisfies(session -> {
            assertThat(session.getId()).isEqualTo(before.getId());
            assertThat(session.getRefreshTokenHash()).isEqualTo(tokenService.hashRefreshToken(newToken));
            assertThat(session.getExpiresAt()).isEqualTo(before.getExpiresAt());
            assertThat(session.getRevokedAt()).isNull();
        });
    }

    // A session with a day left gets a cookie that lasts a day, not a fresh refresh-token-ttl.
    @Test
    void cookieExpiresWithTheSession() {
        register();
        String token = login();
        jdbcClient.sql("update user_sessions set expires_at = now() + interval '1 day'").update();

        Duration left = Duration.ofDays(1);
        refresh(token)
                .expectStatus().isOk()
                .expectCookie().maxAge(COOKIE, seconds ->
                        assertThat(seconds).isBetween(left.minusMinutes(1).toSeconds(), left.toSeconds()));
    }

    @Test
    void missingCookieIsInvalidRefreshToken() {
        expectInvalidRefreshToken(client.post().uri("/api/auth/refresh").exchange());
    }

    @Test
    void unknownTokenIsInvalidRefreshToken() {
        expectInvalidRefreshToken(refresh("not-a-token-the-server-issued"));
    }

    @Test
    void revokedSessionIsInvalidRefreshToken() {
        register();
        String token = login();
        jdbcClient.sql("update user_sessions set revoked_at = now()").update();

        expectInvalidRefreshToken(refresh(token));
    }

    // created_at moves back too: the table checks that a session expires after it started.
    @Test
    void expiredSessionIsInvalidRefreshToken() {
        register();
        String token = login();
        jdbcClient.sql("""
                        update user_sessions
                        set created_at = now() - interval '31 days', expires_at = now() - interval '1 day'
                        """)
                .update();

        expectInvalidRefreshToken(refresh(token));
    }

    // The row lock makes the second request wait, then find the token already replaced.
    @Test
    void sameTokenRefreshedTwiceAtOnceWorksOnlyOnce() throws Exception {
        register();
        String token = login();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> attempt = () -> {
            start.await();
            return refresh(token).returnResult().getStatus().value();
        };

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> first = executor.submit(attempt);
            Future<Integer> second = executor.submit(attempt);
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 401);
        }
    }

    private UUID register() {
        RegisterResponse response = client.post().uri("/api/auth/register")
                .body(new RegisterRequest("Andre", "password123"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(RegisterResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        return response.id();
    }

    private String login() {
        return refreshToken(client.post().uri("/api/auth/login")
                .body(new LoginRequest("Andre", "password123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult());
    }

    private RestTestClient.ResponseSpec refresh(String refreshToken) {
        return client.post().uri("/api/auth/refresh")
                .cookie(COOKIE, refreshToken)
                .exchange();
    }

    private String successfulRefresh(String refreshToken) {
        return refreshToken(refresh(refreshToken)
                .expectStatus().isOk()
                .expectBody()
                .returnResult());
    }

    private static void expectInvalidRefreshToken(RestTestClient.ResponseSpec response) {
        response.expectStatus().isUnauthorized()
                .expectCookie().doesNotExist(COOKIE)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("auth.invalid_refresh_token");
    }

    private static String refreshToken(EntityExchangeResult<byte[]> result) {
        ResponseCookie cookie = result.getResponseCookies().getFirst(COOKIE);

        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

}
