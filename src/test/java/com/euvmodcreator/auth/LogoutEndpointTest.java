package com.euvmodcreator.auth;

import com.euvmodcreator.IntegrationTest;
import com.euvmodcreator.auth.dto.LoginRequest;
import com.euvmodcreator.auth.dto.RegisterRequest;
import com.euvmodcreator.auth.repository.UserSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LogoutEndpointTest extends IntegrationTest {

    private static final String COOKIE = "refresh_token";

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Test
    void revokesTheSessionAndClearsTheCookie() {
        register();

        expectLoggedOut(logout(login()));

        assertThat(userSessionRepository.findAll()).singleElement()
                .satisfies(session -> assertThat(session.getRevokedAt()).isNotNull());
    }

    @Test
    void refreshStopsWorkingAfterLogout() {
        register();
        String token = login();

        logout(token).expectStatus().isNoContent();

        refresh(token)
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("auth.invalid_refresh_token");
    }

    @Test
    void otherSessionsStayLoggedIn() {
        register();
        String laptop = login();
        String phone = login();

        logout(laptop).expectStatus().isNoContent();

        refresh(phone).expectStatus().isOk();
    }

    @Test
    void missingCookieStillLogsOut() {
        expectLoggedOut(client.post().uri("/api/auth/logout").exchange());
    }

    @Test
    void unknownTokenStillLogsOutWithoutTouchingOtherSessions() {
        register();
        login();

        expectLoggedOut(logout("not-a-token-the-server-issued"));

        assertThat(userSessionRepository.findAll()).singleElement()
                .satisfies(session -> assertThat(session.getRevokedAt()).isNull());
    }

    @Test
    void secondLogoutStillLogsOutAndKeepsTheFirstRevocationTime() {
        register();
        String token = login();
        logout(token).expectStatus().isNoContent();
        Instant firstLogout = userSessionRepository.findAll().getFirst().getRevokedAt();

        expectLoggedOut(logout(token));

        assertThat(userSessionRepository.findAll().getFirst().getRevokedAt()).isEqualTo(firstLogout);
    }

    private void register() {
        client.post().uri("/api/auth/register")
                .body(new RegisterRequest("Andre", "password123"))
                .exchange()
                .expectStatus().isCreated();
    }

    private String login() {
        return refreshToken(client.post().uri("/api/auth/login")
                .body(new LoginRequest("Andre", "password123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult());
    }

    private RestTestClient.ResponseSpec logout(String refreshToken) {
        return client.post().uri("/api/auth/logout")
                .cookie(COOKIE, refreshToken)
                .exchange();
    }

    private RestTestClient.ResponseSpec refresh(String refreshToken) {
        return client.post().uri("/api/auth/refresh")
                .cookie(COOKIE, refreshToken)
                .exchange();
    }

    // Same name, path and attributes as the login cookie: with any of them different the browser keeps the real one.
    private static void expectLoggedOut(RestTestClient.ResponseSpec response) {
        response.expectStatus().isNoContent()
                .expectCookie().valueEquals(COOKIE, "")
                .expectCookie().maxAge(COOKIE, Duration.ZERO)
                .expectCookie().path(COOKIE, "/api/auth")
                .expectCookie().httpOnly(COOKIE, true)
                .expectCookie().secure(COOKIE, true)
                .expectCookie().sameSite(COOKIE, "Strict")
                .expectBody().isEmpty();
    }

    private static String refreshToken(EntityExchangeResult<byte[]> result) {
        ResponseCookie cookie = result.getResponseCookies().getFirst(COOKIE);

        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

}
