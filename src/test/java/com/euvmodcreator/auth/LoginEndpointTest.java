package com.euvmodcreator.auth;

import com.euvmodcreator.IntegrationTest;
import com.euvmodcreator.auth.dto.LoginRequest;
import com.euvmodcreator.auth.dto.LoginResponse;
import com.euvmodcreator.auth.dto.RegisterRequest;
import com.euvmodcreator.auth.dto.RegisterResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LoginEndpointTest extends IntegrationTest {

    @Autowired
    private JwtDecoder jwtDecoder;

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

    private String unauthorizedBody(String username, String password) {
        byte[] body = login(username, password)
                .expectStatus().isUnauthorized()
                .expectBody()
                .returnResult()
                .getResponseBodyContent();

        return new String(body, StandardCharsets.UTF_8);
    }

}
