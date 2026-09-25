package com.euvmodcreator.auth;

import com.euvmodcreator.IntegrationTest;
import com.euvmodcreator.auth.dto.LoginRequest;
import com.euvmodcreator.auth.dto.LoginResponse;
import com.euvmodcreator.auth.dto.RegisterRequest;
import com.euvmodcreator.auth.dto.RegisterResponse;
import com.euvmodcreator.auth.dto.UserResponse;
import com.euvmodcreator.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MeEndpointTest extends IntegrationTest {

    private static final String COOKIE = "refresh_token";

    @Autowired
    private UserRepository userRepository;

    @Test
    void returnsTheLoggedInUser() {
        UUID id = register();

        UserResponse response = me(accessToken(login("Andre")))
                .expectStatus().isOk()
                .expectBody(UserResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isEqualTo(new UserResponse(id, "Andre"));
    }

    // Login ignores case; the username comes back as it was registered, not as it was typed.
    @Test
    void returnsTheUsernameAsRegistered() {
        register();

        me(accessToken(login("aNDRE")))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("Andre");
    }

    // The access token from refresh must carry the session id too, or the converter rejects it.
    @Test
    void acceptsTheAccessTokenFromRefresh() {
        register();
        String refreshToken = refreshToken(login("Andre"));

        LoginResponse refreshed = client.post().uri("/api/auth/refresh")
                .cookie(COOKIE, refreshToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(LoginResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(refreshed).isNotNull();
        me(refreshed.accessToken()).expectStatus().isOk();
    }

    @Test
    void missingTokenIsInvalidAccessToken() {
        client.get().uri("/api/users/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("auth.invalid_access_token");
    }

    // The token outlives the row by up to access-token-ttl. A 401, not a 404: the frontend refreshes, refresh fails
    // because the sessions went with the user, and the user lands on the login page.
    @Test
    void deletedUserIsInvalidAccessToken() {
        UUID id = register();
        String accessToken = accessToken(login("Andre"));
        userRepository.deleteById(id);

        me(accessToken)
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("auth.invalid_access_token");
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

    private EntityExchangeResult<LoginResponse> login(String username) {
        return client.post().uri("/api/auth/login")
                .body(new LoginRequest(username, "password123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LoginResponse.class)
                .returnResult();
    }

    private RestTestClient.ResponseSpec me(String accessToken) {
        return client.get().uri("/api/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    private static String accessToken(EntityExchangeResult<LoginResponse> result) {
        LoginResponse response = result.getResponseBody();

        assertThat(response).isNotNull();
        return response.accessToken();
    }

    private static String refreshToken(EntityExchangeResult<LoginResponse> result) {
        ResponseCookie cookie = result.getResponseCookies().getFirst(COOKIE);

        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

}
