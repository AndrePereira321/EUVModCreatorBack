package com.euvmodcreator.auth;

import com.euvmodcreator.IntegrationTest;
import com.euvmodcreator.auth.dto.LoginRequest;
import com.euvmodcreator.auth.dto.RegisterRequest;
import com.euvmodcreator.auth.entity.UserSession;
import com.euvmodcreator.auth.repository.UserSessionRepository;
import com.euvmodcreator.auth.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

// The cron is off in tests (application-test.properties), so each test runs the job itself. Retention is the
// default 30 days.
class SessionCleanupJobTest extends IntegrationTest {

    @Autowired
    private SessionCleanupJob sessionCleanupJob;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void deletesSessionsThatEndedMoreThan30DaysAgo() {
        register();
        String live = login();
        String expiredLongAgo = login();
        String expiredRecently = login();
        String revokedLongAgo = login();
        String revokedRecently = login();
        expireDaysAgo(expiredLongAgo, 31);
        expireDaysAgo(expiredRecently, 29);
        revokeDaysAgo(revokedLongAgo, 31);
        revokeDaysAgo(revokedRecently, 29);

        sessionCleanupJob.deleteEndedSessions();

        assertThat(userSessionRepository.findAll())
                .extracting(UserSession::getRefreshTokenHash)
                .containsExactlyInAnyOrder(hash(live), hash(expiredRecently), hash(revokedRecently));
        client.post().uri("/api/auth/refresh").cookie("refresh_token", live).exchange().expectStatus().isOk();
    }

    @Test
    void nothingToDeleteIsFine() {
        sessionCleanupJob.deleteEndedSessions();

        assertThat(userSessionRepository.count()).isZero();
    }

    private void register() {
        client.post().uri("/api/auth/register")
                .body(new RegisterRequest("Andre", "password123"))
                .exchange()
                .expectStatus().isCreated();
    }

    private String login() {
        ResponseCookie cookie = client.post().uri("/api/auth/login")
                .body(new LoginRequest("Andre", "password123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseCookies()
                .getFirst("refresh_token");

        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    // created_at moves back too: the table checks that a session expires after it started.
    private void expireDaysAgo(String refreshToken, int days) {
        jdbcClient.sql("""
                        update user_sessions
                        set created_at = now() - (:days + 30) * interval '1 day',
                            expires_at = now() - :days * interval '1 day'
                        where refresh_token_hash = :hash
                        """)
                .param("days", days)
                .param("hash", hash(refreshToken))
                .update();
    }

    private void revokeDaysAgo(String refreshToken, int days) {
        jdbcClient.sql("""
                        update user_sessions
                        set revoked_at = now() - :days * interval '1 day'
                        where refresh_token_hash = :hash
                        """)
                .param("days", days)
                .param("hash", hash(refreshToken))
                .update();
    }

    private String hash(String refreshToken) {
        return tokenService.hashRefreshToken(refreshToken);
    }

}
