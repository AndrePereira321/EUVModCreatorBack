package com.euvmodcreator.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.euvmodcreator.IntegrationTest;
import com.euvmodcreator.auth.dto.LoginRequest;
import com.euvmodcreator.auth.dto.LoginResponse;
import com.euvmodcreator.auth.dto.RegisterRequest;
import com.euvmodcreator.auth.dto.RegisterResponse;
import com.euvmodcreator.auth.security.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AuthLoggingTest extends IntegrationTest {

    private static final String COOKIE = "refresh_token";

    private static final String PASSWORD = "correct-horse-battery";

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private final Logger appLogger = (Logger) LoggerFactory.getLogger("com.euvmodcreator");

    private Level levelBefore;

    @Autowired
    private TokenService tokenService;

    // DEBUG, so the lines production doesn't write are checked too: local runs write them.
    @BeforeEach
    void captureLogs() {
        levelBefore = appLogger.getLevel();
        appLogger.setLevel(Level.DEBUG);
        appender.start();
        rootLogger().addAppender(appender);
    }

    @AfterEach
    void stopCapturing() {
        rootLogger().detachAppender(appender);
        appender.stop();
        appLogger.setLevel(levelBefore);
    }

    // Covers the username field too: a user sometimes types the password there.
    @Test
    void noPasswordOrTokenReachesTheLogs() {
        register();
        login(PASSWORD, PASSWORD).expectStatus().isUnauthorized();
        login("Andre", PASSWORD + "-wrong").expectStatus().isUnauthorized();

        EntityExchangeResult<LoginResponse> loggedIn = successfulLogin();
        String accessToken = loggedIn.getResponseBody().accessToken();
        String refreshToken = refreshToken(loggedIn);

        client.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk();

        EntityExchangeResult<LoginResponse> refreshed = refresh(refreshToken)
                .expectStatus().isOk()
                .expectBody(LoginResponse.class)
                .returnResult();
        String rotatedAccessToken = refreshed.getResponseBody().accessToken();
        String rotatedRefreshToken = refreshToken(refreshed);

        refresh(refreshToken).expectStatus().isUnauthorized();
        client.post().uri("/api/auth/logout").cookie(COOKIE, rotatedRefreshToken).exchange().expectStatus().isNoContent();
        refresh(rotatedRefreshToken).expectStatus().isUnauthorized();

        assertThat(logText())
                .contains("logged in", "logged out")
                .doesNotContain(PASSWORD, accessToken, rotatedAccessToken, refreshToken, rotatedRefreshToken)
                .doesNotContain(tokenService.hashRefreshToken(refreshToken), tokenService.hashRefreshToken(rotatedRefreshToken));
    }

    @Test
    void linesWrittenDuringARequestCarryItsIdAndClientIp() {
        UUID id = register();

        String requestId = successfulLogin().getResponseHeaders().getFirst(REQUEST_ID_HEADER);

        assertThat(requestId).isNotBlank();
        assertThat(linesFrom(AuthService.class.getName()))
                .filteredOn(line -> line.getFormattedMessage().startsWith("User " + id + " logged in"))
                .singleElement()
                .satisfies(line -> assertThat(line.getMDCPropertyMap())
                        .containsEntry("requestId", requestId)
                        .containsEntry("clientIp", "127.0.0.1"));
    }

    // The request id is set before Spring Security's filters run, so their lines carry it as well.
    @Test
    void securityFilterLinesCarryTheRequestIdToo() {
        String requestId = client.get().uri("/api/users/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .returnResult()
                .getResponseHeaders()
                .getFirst(REQUEST_ID_HEADER);

        assertThat(linesFrom("com.euvmodcreator.auth.security.AccessTokenEntryPoint"))
                .singleElement()
                .satisfies(line -> assertThat(line.getMDCPropertyMap()).containsEntry("requestId", requestId));
    }

    // Four ordinary failures, then one warning when the fifth locks the username; the rejected sixth adds nothing.
    @Test
    void failedLoginThatLocksTheUsernameIsAWarning() {
        UUID id = register();

        for (int i = 0; i < 5; i++) {
            login("Andre", "wrong-password").expectStatus().isUnauthorized();
        }
        login("Andre", "wrong-password").expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        List<ILoggingEvent> failures = linesFrom(AuthService.class.getName()).stream()
                .filter(line -> line.getFormattedMessage().startsWith("Login failed"))
                .toList();
        assertThat(failures).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.INFO, Level.INFO, Level.INFO, Level.INFO, Level.WARN);
        assertThat(failures.getLast().getFormattedMessage()).isEqualTo("Login failed for user " + id + ", which is now locked");
    }

    @Test
    void unknownUsernameIsLoggedWithoutTheName() {
        login("Nobody", PASSWORD).expectStatus().isUnauthorized();

        assertThat(linesFrom(AuthService.class.getName()))
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Login failed for an unknown username");
    }

    private UUID register() {
        RegisterResponse response = client.post().uri("/api/auth/register")
                .body(new RegisterRequest("Andre", PASSWORD))
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

    private EntityExchangeResult<LoginResponse> successfulLogin() {
        return login("Andre", PASSWORD)
                .expectStatus().isOk()
                .expectBody(LoginResponse.class)
                .returnResult();
    }

    private RestTestClient.ResponseSpec refresh(String refreshToken) {
        return client.post().uri("/api/auth/refresh")
                .cookie(COOKIE, refreshToken)
                .exchange();
    }

    private static String refreshToken(EntityExchangeResult<?> result) {
        ResponseCookie cookie = result.getResponseCookies().getFirst(COOKIE);

        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    private List<ILoggingEvent> lines() {
        // The server's threads append under the appender's lock; taking it here makes their writes visible.
        synchronized (appender) {
            return new ArrayList<>(appender.list);
        }
    }

    private List<ILoggingEvent> linesFrom(String loggerName) {
        return lines().stream().filter(line -> line.getLoggerName().equals(loggerName)).toList();
    }

    private String logText() {
        return lines().stream()
                .map(line -> line.getFormattedMessage()
                        + " " + line.getMDCPropertyMap()
                        + (line.getThrowableProxy() == null ? "" : " " + ThrowableProxyUtil.asString(line.getThrowableProxy())))
                .collect(Collectors.joining("\n"));
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

}
