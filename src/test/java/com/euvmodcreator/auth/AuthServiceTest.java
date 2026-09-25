package com.euvmodcreator.auth;

import com.euvmodcreator.auth.dto.LoginRequest;
import com.euvmodcreator.auth.dto.RegisterRequest;
import com.euvmodcreator.auth.entity.User;
import com.euvmodcreator.auth.entity.UserAuth;
import com.euvmodcreator.auth.entity.UserSession;
import com.euvmodcreator.auth.exception.InvalidCredentialsException;
import com.euvmodcreator.auth.exception.InvalidRefreshTokenException;
import com.euvmodcreator.auth.exception.UsernameTakenException;
import com.euvmodcreator.auth.model.AuthResult;
import com.euvmodcreator.auth.repository.LoginCredentials;
import com.euvmodcreator.auth.repository.UserAuthRepository;
import com.euvmodcreator.auth.repository.UserRepository;
import com.euvmodcreator.auth.repository.UserSessionRepository;
import com.euvmodcreator.auth.security.RefreshToken;
import com.euvmodcreator.auth.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final RegisterRequest REGISTER = new RegisterRequest("Andre", "password123");

    private static final LoginRequest LOGIN = new LoginRequest("andre", "password123");

    private static final String DUMMY_HASH = "{bcrypt}dummy";

    private static final String REAL_HASH = "{bcrypt}real";

    private static final RefreshToken REFRESH_TOKEN =
            new RefreshToken("refresh-token", Instant.parse("2026-10-25T12:00:00Z"));

    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAuthRepository userAuthRepository;

    @Mock
    private UserSessionRepository userSessionRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    @InjectMocks
    private AuthService authService;

    // Spring calls @PostConstruct once the dependencies are injected; @InjectMocks doesn't, so the test has to.
    @BeforeEach
    void initLikeSpringWould() {
        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
        authService.init();
    }

    @Test
    void rejectsTakenUsernameWithoutSavingAnything() {
        when(userRepository.existsByUsernameIgnoreCase("Andre")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(REGISTER)).isInstanceOf(UsernameTakenException.class);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(userAuthRepository);
        // Not verifyNoInteractions: init() already called encode() for the dummy hash.
        verify(passwordEncoder, never()).encode(REGISTER.password());
    }

    @Test
    void savesUserThenCredentialsHoldingTheHash() {
        UUID id = UUID.randomUUID();
        // The real repository assigns the id on save; the mock has to do it by hand.
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", id);
            return user;
        });
        when(passwordEncoder.encode("password123")).thenReturn("{bcrypt}hashed");

        User registered = authService.register(REGISTER);

        assertThat(registered.getId()).isEqualTo(id);
        assertThat(registered.getUsername()).isEqualTo("Andre");

        ArgumentCaptor<UserAuth> savedAuth = ArgumentCaptor.forClass(UserAuth.class);
        verify(userAuthRepository).save(savedAuth.capture());
        assertThat(savedAuth.getValue().getUserId()).isEqualTo(id);
        assertThat(savedAuth.getValue().getPasswordHash()).isEqualTo("{bcrypt}hashed");
    }

    @Test
    void loginReturnsAccessTokenAndRefreshToken() {
        stubSuccessfulLogin();

        AuthResult result = authService.login(LOGIN);

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo(REFRESH_TOKEN);
    }

    @Test
    void loginSavesSessionHoldingTheTokenHashNotTheToken() {
        User user = stubSuccessfulLogin();

        authService.login(LOGIN);

        ArgumentCaptor<UserSession> savedSession = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionRepository).save(savedSession.capture());
        assertThat(savedSession.getValue().getUserId()).isEqualTo(user.getId());
        assertThat(savedSession.getValue().getRefreshTokenHash()).isEqualTo("hashed-refresh-token");
        assertThat(savedSession.getValue().getExpiresAt()).isEqualTo(REFRESH_TOKEN.expiresAt());
    }

    // The sid claim is how a later request knows which session it belongs to.
    @Test
    void loginIssuesTheAccessTokenForTheNewSession() {
        User user = stubSuccessfulLogin();

        authService.login(LOGIN);

        verify(tokenService).issueAccessToken(user.getId(), SESSION_ID);
    }

    @Test
    void loginRejectsWrongPassword() {
        when(userAuthRepository.findLoginCredentials("andre"))
                .thenReturn(Optional.of(new LoginCredentials(new User(), REAL_HASH)));
        when(passwordEncoder.matches("password123", REAL_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authService.login(LOGIN)).isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(tokenService, userSessionRepository);
    }

    @Test
    void loginStillRunsBcryptForUnknownUser() {
        when(userAuthRepository.findLoginCredentials("andre")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(LOGIN)).isInstanceOf(InvalidCredentialsException.class);

        // Against the dummy hash: the same BCrypt cost as a wrong password, so timing can't reveal the difference.
        verify(passwordEncoder).matches("password123", DUMMY_HASH);
        verifyNoInteractions(tokenService, userSessionRepository);
    }

    @Test
    void loginRejectsUnknownUserEvenIfTheDummyHashMatches() {
        when(userAuthRepository.findLoginCredentials("andre")).thenReturn(Optional.empty());
        when(passwordEncoder.matches("password123", DUMMY_HASH)).thenReturn(true);

        assertThatThrownBy(() -> authService.login(LOGIN)).isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(tokenService, userSessionRepository);
    }

    @Test
    void refreshRotatesTheTokenOnTheSameSessionAndKeepsItsExpiry() {
        Instant expiresAt = Instant.now().plus(Duration.ofDays(10));
        UserSession session = storedSession(expiresAt);
        RefreshToken rotated = new RefreshToken("new-token", expiresAt);
        when(tokenService.newRefreshToken(expiresAt)).thenReturn(rotated);
        when(tokenService.hashRefreshToken("new-token")).thenReturn("hashed-new-token");
        when(tokenService.issueAccessToken(session.getUserId(), SESSION_ID)).thenReturn("access-token");

        AuthResult result = authService.refresh("old-token");

        verify(tokenService).issueAccessToken(session.getUserId(), SESSION_ID);
        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo(rotated);
        assertThat(session.getRefreshTokenHash()).isEqualTo("hashed-new-token");
        assertThat(session.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(session.getRevokedAt()).isNull();
    }

    @Test
    void refreshRejectsMissingToken() {
        assertThatThrownBy(() -> authService.refresh(null)).isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> authService.refresh(" ")).isInstanceOf(InvalidRefreshTokenException.class);

        verifyNoInteractions(tokenService, userSessionRepository);
    }

    @Test
    void refreshRejectsUnknownToken() {
        when(tokenService.hashRefreshToken("old-token")).thenReturn("hashed-old-token");
        when(userSessionRepository.findByRefreshTokenHash("hashed-old-token")).thenReturn(Optional.empty());

        assertRefreshRejected();
    }

    @Test
    void refreshRejectsRevokedSession() {
        UserSession session = storedSession(Instant.now().plus(Duration.ofDays(10)));
        session.setRevokedAt(Instant.now().minus(Duration.ofHours(1)));

        assertRefreshRejected();
        assertThat(session.getRefreshTokenHash()).isEqualTo("hashed-old-token");
    }

    @Test
    void refreshRejectsExpiredSession() {
        UserSession session = storedSession(Instant.now().minusSeconds(1));

        assertRefreshRejected();
        assertThat(session.getRefreshTokenHash()).isEqualTo("hashed-old-token");
    }

    @Test
    void logoutRevokesTheSession() {
        UserSession session = storedSession(Instant.now().plus(Duration.ofDays(10)));

        authService.logout("old-token");

        assertThat(session.getRevokedAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void logoutKeepsTheFirstRevocationTime() {
        UserSession session = storedSession(Instant.now().plus(Duration.ofDays(10)));
        Instant firstLogout = Instant.now().minus(Duration.ofHours(1));
        session.setRevokedAt(firstLogout);

        authService.logout("old-token");

        assertThat(session.getRevokedAt()).isEqualTo(firstLogout);
    }

    @Test
    void logoutIgnoresMissingToken() {
        assertThatNoException().isThrownBy(() -> authService.logout(null));
        assertThatNoException().isThrownBy(() -> authService.logout(" "));

        verifyNoInteractions(tokenService, userSessionRepository);
    }

    @Test
    void logoutIgnoresUnknownToken() {
        when(tokenService.hashRefreshToken("old-token")).thenReturn("hashed-old-token");
        when(userSessionRepository.findByRefreshTokenHash("hashed-old-token")).thenReturn(Optional.empty());

        assertThatNoException().isThrownBy(() -> authService.logout("old-token"));
    }

    private User stubSuccessfulLogin() {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        when(userAuthRepository.findLoginCredentials("andre"))
                .thenReturn(Optional.of(new LoginCredentials(user, REAL_HASH)));
        when(passwordEncoder.matches("password123", REAL_HASH)).thenReturn(true);
        when(tokenService.newRefreshToken()).thenReturn(REFRESH_TOKEN);
        when(tokenService.hashRefreshToken("refresh-token")).thenReturn("hashed-refresh-token");
        // The real repository assigns the id on save; the mock has to do it by hand.
        when(userSessionRepository.save(any(UserSession.class))).thenAnswer(invocation -> {
            UserSession session = invocation.getArgument(0);
            ReflectionTestUtils.setField(session, "id", SESSION_ID);
            return session;
        });
        when(tokenService.issueAccessToken(user.getId(), SESSION_ID)).thenReturn("access-token");
        return user;
    }

    private UserSession storedSession(Instant expiresAt) {
        UserSession session = new UserSession();
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        session.setUserId(UUID.randomUUID());
        session.setRefreshTokenHash("hashed-old-token");
        session.setExpiresAt(expiresAt);

        when(tokenService.hashRefreshToken("old-token")).thenReturn("hashed-old-token");
        when(userSessionRepository.findByRefreshTokenHash("hashed-old-token")).thenReturn(Optional.of(session));
        return session;
    }

    private void assertRefreshRejected() {
        assertThatThrownBy(() -> authService.refresh("old-token")).isInstanceOf(InvalidRefreshTokenException.class);

        verify(tokenService, never()).newRefreshToken(any());
        verify(tokenService, never()).issueAccessToken(any(), any());
    }

}
