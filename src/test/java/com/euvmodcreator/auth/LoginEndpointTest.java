package com.euvmodcreator.auth;

import com.euvmodcreator.IntegrationTest;
import com.euvmodcreator.auth.dto.LoginRequest;
import com.euvmodcreator.auth.dto.LoginResponse;
import com.euvmodcreator.auth.dto.RegisterRequest;
import com.euvmodcreator.auth.dto.RegisterResponse;
import com.euvmodcreator.auth.entity.UserSession;
import com.euvmodcreator.auth.repository.UserSessionRepository;
import com.euvmodcreator.auth.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LoginEndpointTest extends IntegrationTest {

    private static final String COOKIE = "refresh_token";

    private static final Duration REFRESH_TTL = Duration.ofDays(30);

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Test
    void returnsAccessTokenForTheUserAndTheNewSession() {
        UUID id = register("Andre", "password123");

        LoginResponse response = login("Andre", "password123")
                .expectStatus().isOk()
                .expectBody(LoginResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        Jwt jwt = jwtDecoder.decode(response.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(id.toString());
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(userSessionRepository.findAll().getFirst().getId().toString());

        // No controller behind this path: a 404 means the token got through security.
        client.get().uri("/api/nothing-here")
                .header("Authorization", "Bearer " + response.accessToken())
                .exchange()
                .expectStatus().isNotFound();
    }

    // SameSite=Strict is what makes switching CSRF protection off safe; HttpOnly keeps the token out of JavaScript.
    @Test
    void setsRefreshTokenCookieThatScriptsAndOtherSitesCannotUse() {
        register("Andre", "password123");

        login("Andre", "password123")
                .expectStatus().isOk()
                .expectCookie().httpOnly(COOKIE, true)
                .expectCookie().secure(COOKIE, true)
                .expectCookie().sameSite(COOKIE, "Strict")
                .expectCookie().path(COOKIE, "/api/auth")
                .expectCookie().maxAge(COOKIE, seconds ->
                        assertThat(seconds).isBetween(REFRESH_TTL.minusMinutes(1).toSeconds(), REFRESH_TTL.toSeconds()));
    }

    @Test
    void refreshTokenTravelsOnlyInTheCookie() {
        register("Andre", "password123");

        EntityExchangeResult<byte[]> result = successfulLogin("Andre", "password123");

        assertThat(new String(result.getResponseBodyContent(), StandardCharsets.UTF_8))
                .doesNotContain(refreshToken(result));
    }

    // Refresh looks the session up by hashing the cookie, so the row must hold exactly that hash.
    @Test
    void storesSessionHoldingTheHashOfTheCookieNotTheCookie() {
        UUID id = register("Andre", "password123");

        String refreshToken = refreshToken(successfulLogin("Andre", "password123"));

        assertThat(userSessionRepository.findAll()).singleElement().satisfies(session -> {
            assertThat(session.getUserId()).isEqualTo(id);
            assertThat(session.getRefreshTokenHash())
                    .isEqualTo(tokenService.hashRefreshToken(refreshToken))
                    .isNotEqualTo(refreshToken);
            assertThat(session.getExpiresAt()).isCloseTo(Instant.now().plus(REFRESH_TTL), within(1, ChronoUnit.MINUTES));
            assertThat(session.getRevokedAt()).isNull();
        });
    }

    // Two devices: neither sends the other's cookie, so both sessions stay live.
    @Test
    void everyLoginStartsItsOwnSession() {
        register("Andre", "password123");

        String first = refreshToken(successfulLogin("Andre", "password123"));
        String second = refreshToken(successfulLogin("Andre", "password123"));

        assertThat(second).isNotEqualTo(first);
        assertThat(userSessionRepository.findAll()).hasSize(2)
                .allSatisfy(session -> assertThat(session.getRevokedAt()).isNull());
    }

    // The response overwrites the browser's cookie, so the session behind it would live on with nobody holding it.
    @Test
    void loginRevokesTheSessionOfTheCookieItReplaces() {
        register("Andre", "password123");
        String first = refreshToken(successfulLogin("Andre", "password123"));

        String second = refreshToken(successfulLogin("Andre", "password123", first));

        UserSession revoked = session(first);
        assertThat(revoked.getRevokedAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(revoked.getUpdatedAt()).isEqualTo(revoked.getRevokedAt());
        assertThat(session(second).getRevokedAt()).isNull();
        client.post().uri("/api/auth/refresh").cookie(COOKIE, first).exchange().expectStatus().isUnauthorized();
    }

    // A typo while switching accounts must not log anybody out.
    @Test
    void failedLoginKeepsTheSessionOfItsCookie() {
        register("Andre", "password123");
        String token = refreshToken(successfulLogin("Andre", "password123"));

        login("Andre", "wrongpassword", token).expectStatus().isUnauthorized();

        assertThat(session(token).getRevokedAt()).isNull();
    }

    @Test
    void loginRevokesTheCookiesSessionWhicheverUserItBelongsTo() {
        register("Andre", "password123");
        register("Bruno", "password123");
        String andresToken = refreshToken(successfulLogin("Andre", "password123"));

        successfulLogin("Bruno", "password123", andresToken);

        assertThat(session(andresToken).getRevokedAt()).isNotNull();
    }

    @Test
    void unknownCookieDoesNotStopLogin() {
        register("Andre", "password123");

        successfulLogin("Andre", "password123", "not-a-token-the-server-issued");

        assertThat(userSessionRepository.count()).isEqualTo(1);
    }

    @Test
    void alreadyRevokedCookieKeepsItsFirstRevocationTime() {
        register("Andre", "password123");
        String token = refreshToken(successfulLogin("Andre", "password123"));
        client.post().uri("/api/auth/logout").cookie(COOKIE, token).exchange().expectStatus().isNoContent();
        Instant loggedOut = session(token).getRevokedAt();

        successfulLogin("Andre", "password123", token);

        assertThat(session(token).getRevokedAt()).isEqualTo(loggedOut);
    }

    @Test
    void usernameIgnoresCase() {
        register("Andre", "password123");

        login("aNDRE", "password123").expectStatus().isOk();
    }

    @Test
    void wrongPasswordIsInvalidCredentials() {
        register("Andre", "password123");

        login("Andre", "wrongpassword")
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("auth.invalid_credentials");
    }

    // A different answer for an unknown username would tell an attacker which usernames exist.
    @Test
    void unknownUserGetsExactlyTheWrongPasswordResponse() {
        register("Andre", "password123");

        String wrongPassword = unauthorizedBody("Andre", "wrongpassword");
        String unknownUser = unauthorizedBody("nobody", "password123");

        assertThat(unknownUser).isEqualTo(wrongPassword);
    }

    @Test
    void failedLoginSetsNoCookieAndStartsNoSession() {
        register("Andre", "password123");

        login("Andre", "wrongpassword").expectStatus().isUnauthorized().expectCookie().doesNotExist(COOKIE);
        login("nobody", "password123").expectStatus().isUnauthorized().expectCookie().doesNotExist(COOKIE);

        assertThat(userSessionRepository.count()).isZero();
    }

    @Test
    void rejectsBlankCredentials() {
        login("", "")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("validation_failed")
                .jsonPath("$.errors[?(@.field == 'username' && @.code == 'NotBlank')]").exists()
                .jsonPath("$.errors[?(@.field == 'password' && @.code == 'NotBlank')]").exists();
    }

    // The sixth attempt is refused before the password is checked, so even the right one can't get through.
    @Test
    void fiveFailedLoginsLockTheUsername() {
        register("Andre", "password123");
        failLogins("Andre", 5);

        expectRateLimited(login("Andre", "wrong-password"), 300);
        expectRateLimited(login("Andre", "password123"), 300);
    }

    @Test
    void lockIgnoresUsernameCase() {
        register("Andre", "password123");
        failLogins("Andre", 5);

        expectRateLimited(login("aNDRE", "password123"), 300);
    }

    // Keyed on the submitted name, so a 429 can't reveal which usernames exist.
    @Test
    void unknownUsernameIsLockedTheSameWay() {
        failLogins("nobody_here", 5);

        expectRateLimited(login("nobody_here", "wrong-password"), 300);
    }

    // Without the clear, the success would be the fifth counted attempt and the next failure a 429.
    @Test
    void successfulLoginClearsTheFailedAttempts() {
        register("Andre", "password123");
        failLogins("Andre", 4);
        successfulLogin("Andre", "password123");

        failLogins("Andre", 4);
    }

    // A different username each time, so only the per-IP limit applies: 10 a minute, one back every 6 seconds.
    @Test
    void eleventhLoginFromOneIpWithinAMinuteIsRateLimited() {
        for (int i = 0; i < 10; i++) {
            login("nobody_" + i, "wrong-password").expectStatus().isUnauthorized();
        }

        expectRateLimited(login("nobody_10", "wrong-password"), 6);
    }

    // The interceptor runs before the body is parsed, so rejected requests use up the limit too.
    @Test
    void invalidRequestsCountTowardsTheIpLimit() {
        for (int i = 0; i < 10; i++) {
            login("", "").expectStatus().isBadRequest();
        }

        expectRateLimited(login("", ""), 6);
    }

    private void failLogins(String username, int times) {
        for (int i = 0; i < times; i++) {
            login(username, "wrong-password")
                    .expectStatus().isUnauthorized()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("auth.invalid_credentials");
        }
    }

    private static void expectRateLimited(RestTestClient.ResponseSpec response, long maxRetryAfterSeconds) {
        response.expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().value(HttpHeaders.RETRY_AFTER,
                        retryAfter -> assertThat(Long.parseLong(retryAfter)).isBetween(1L, maxRetryAfterSeconds))
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("rate_limited")
                .jsonPath("$.params.retryAfterSeconds").isNumber();
    }

    private UUID register(String username, String password) {
        RegisterResponse response = client.post().uri("/api/auth/register")
                .body(new RegisterRequest(username, password))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(RegisterResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        return response.id();
    }

    private RestTestClient.ResponseSpec login(String username, String password) {
        return client.post().uri("/api/auth/login")
                .body(new LoginRequest(username, password))
                .exchange();
    }

    private RestTestClient.ResponseSpec login(String username, String password, String refreshToken) {
        return client.post().uri("/api/auth/login")
                .cookie(COOKIE, refreshToken)
                .body(new LoginRequest(username, password))
                .exchange();
    }

    private EntityExchangeResult<byte[]> successfulLogin(String username, String password) {
        return login(username, password)
                .expectStatus().isOk()
                .expectBody()
                .returnResult();
    }

    private EntityExchangeResult<byte[]> successfulLogin(String username, String password, String refreshToken) {
        return login(username, password, refreshToken)
                .expectStatus().isOk()
                .expectBody()
                .returnResult();
    }

    // Not findByRefreshTokenHash: its row lock needs a transaction, and the test has none.
    private UserSession session(String refreshToken) {
        String hash = tokenService.hashRefreshToken(refreshToken);
        return userSessionRepository.findAll().stream()
                .filter(session -> session.getRefreshTokenHash().equals(hash))
                .findFirst()
                .orElseThrow();
    }

    private static String refreshToken(EntityExchangeResult<byte[]> result) {
        ResponseCookie cookie = result.getResponseCookies().getFirst(COOKIE);

        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    private String unauthorizedBody(String username, String password) {
        byte[] body = login(username, password)
                .expectStatus().isUnauthorized()
                .expectBody()
                .returnResult()
                .getResponseBodyContent();

        return new String(body, StandardCharsets.UTF_8);
    }

}
