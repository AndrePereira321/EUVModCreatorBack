package com.euvmodcreator.auth;

import com.euvmodcreator.IntegrationTest;
import com.euvmodcreator.auth.dto.RegisterRequest;
import com.euvmodcreator.auth.dto.RegisterResponse;
import com.euvmodcreator.auth.repository.UserAuthRepository;
import com.euvmodcreator.auth.repository.UserRepository;
import com.euvmodcreator.auth.repository.UserSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterEndpointTest extends IntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserAuthRepository userAuthRepository;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registersUserAndStoresOnlyAPasswordHash() {
        RegisterResponse response = register("Andre", "password123")
                .expectStatus().isCreated()
                .expectBody(RegisterResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.id()).isNotNull();
        assertThat(response.username()).isEqualTo("Andre");

        assertThat(userAuthRepository.findAll()).singleElement().satisfies(auth -> {
            assertThat(auth.getUserId()).isEqualTo(response.id());
            assertThat(auth.getPasswordHash()).startsWith("{bcrypt}");
            assertThat(passwordEncoder.matches("password123", auth.getPasswordHash())).isTrue();
        });
    }

    // Sessions start at login only; the frontend calls login right after register.
    @Test
    void registerDoesNotLogIn() {
        register("Andre", "password123")
                .expectStatus().isCreated()
                .expectCookie().doesNotExist("refresh_token");

        assertThat(userSessionRepository.count()).isZero();
    }

    @Test
    void rejectsUsernameThatDiffersOnlyInCase() {
        register("Andre", "password123").expectStatus().isCreated();

        register("aNDRE", "password123")
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("auth.username_taken");

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void reportsEveryFailedRuleWithItsCodeAndParams() {
        register("a b", "tiny7")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("validation_failed")
                .jsonPath("$.errors[?(@.field == 'username' && @.code == 'Pattern')]").exists()
                .jsonPath("$.errors[?(@.field == 'password' && @.code == 'Size')].params.min").isEqualTo(8)
                .jsonPath("$.errors[?(@.field == 'password' && @.code == 'Size')].params.max").isEqualTo(32);
    }

    @Test
    void neverEchoesThePasswordBack() {
        register("andre", "tiny7")
                .expectStatus().isBadRequest()
                .expectBody()
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent(), StandardCharsets.UTF_8))
                        .doesNotContain("tiny7"));
    }

    @Test
    void rejectsMalformedJson() {
        client.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"username\":")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("bad_request");
    }

    private RestTestClient.ResponseSpec register(String username, String password) {
        return client.post().uri("/api/auth/register")
                .body(new RegisterRequest(username, password))
                .exchange();
    }

}
