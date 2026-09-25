package com.euvmodcreator.auth;

import com.euvmodcreator.IntegrationTest;
import com.euvmodcreator.auth.dto.LoginRequest;
import com.euvmodcreator.auth.dto.LoginResponse;
import com.euvmodcreator.auth.dto.RegisterRequest;
import com.euvmodcreator.auth.dto.RegisterResponse;
import com.euvmodcreator.auth.repository.UserSessionRepository;
import com.euvmodcreator.auth.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
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
    void returnsAccessTokenForTheUser() {
        UUID id = register("Andre", "password123");

        LoginResponse response = login("Andre", "password123")
                .expectStatus().isOk()
                .expectBody(LoginResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(jwtDecoder.decode(response.accessToken()).getSubject()).isEqualTo(id.toString());

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

    @Test
    void everyLoginStartsItsOwnSession() {
        register("Andre", "password123");

        String first = refreshToken(successfulLogin("Andre", "password123"));
        String second = refreshToken(successfulLogin("Andre", "password123"));

        assertThat(second).isNotEqualTo(first);
        assertThat(userSessionRepository.count()).isEqualTo(2);
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

    private EntityExchangeResult<byte[]> successfulLogin(String username, String password) {
        return login(username, password)
                .expectStatus().isOk()
                .expectBody()
                .returnResult();
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
