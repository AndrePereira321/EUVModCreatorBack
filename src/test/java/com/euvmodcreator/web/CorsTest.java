package com.euvmodcreator.web;

import com.euvmodcreator.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

// The origin comes from application-test.properties, as the Vite dev server's would from application-local.
class CorsTest extends IntegrationTest {

    private static final String FRONTEND = "http://localhost:5173";

    // The preflight carries no token. If Spring Security saw it before CORS did, this would be a 401.
    @Test
    void preflightForAProtectedEndpointIsAllowed() {
        client.options().uri("/api/users/me")
                .header(HttpHeaders.ORIGIN, FRONTEND)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        headers -> assertThat(headers).containsIgnoringCase("authorization"));
    }

    // Without Allow-Credentials the browser drops the response, and the refresh cookie with it.
    @Test
    void responseToTheFrontendAllowsCredentials() {
        client.post().uri("/api/auth/logout")
                .header(HttpHeaders.ORIGIN, FRONTEND)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }

    @Test
    void preflightFromAnotherOriginIsRejected() {
        client.options().uri("/api/users/me")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void requestFromAnotherOriginIsRejected() {
        client.post().uri("/api/auth/logout")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

}
