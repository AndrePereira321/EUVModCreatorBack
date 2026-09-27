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
import com.euvmodcreator.ratelimit.Lockout;
import com.euvmodcreator.ratelimit.RateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

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

    @Mock
    private TransactionOperations transactionOperations;

    @Mock
    private Lockout loginLockout;

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

        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(transactionOperations, userAuthRepository);
        // Not verifyNoInteractions: init() already called encode() for the dummy hash.
        verify(passwordEncoder, never()).encode(REGISTER.password());
    }

    @Test
    void savesUserThenCredentialsHoldingTheHash() {
        UUID id = stubSuccessfulRegister();

        User registered = authService.register(REGISTER);

        assertThat(registered.getId()).isEqualTo(id);
        assertThat(registered.getUsername()).isEqualTo("Andre");

        ArgumentCaptor<UserAuth> savedAuth = ArgumentCaptor.forClass(UserAuth.class);
        verify(userAuthRepository).save(savedAuth.capture());
        assertThat(savedAuth.getValue().getUserId()).isEqualTo(id);
        assertThat(savedAuth.getValue().getPasswordHash()).isEqualTo("{bcrypt}hashed");
    }

    // A transaction holds a pooled connection from its start, so BCrypt must finish before one opens.
    @Test
    void registerHashesThePasswordBeforeTheTransactionStarts() {
        stubSuccessfulRegister();

        authService.register(REGISTER);

        InOrder inOrder = inOrder(passwordEncoder, transactionOperations);
        inOrder.verify(passwordEncoder).encode("password123");
        inOrder.verify(transactionOperations).execute(any());
    }

    // Both registrations passed the existence check; the unique index stopped this one.
    @Test
    void usernameTakenWhileHashingIsUsernameTaken() {
        when(passwordEncoder.encode("password123")).thenReturn("{bcrypt}hashed");
        runTransactionsInline();
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> authService.register(REGISTER)).isInstanceOf(UsernameTakenException.class);

        verifyNoInteractions(userAuthRepository);
    }

    @Test
    void loginReturnsAccessTokenAndRefreshToken() {
        stubSuccessfulLogin();

        AuthResult result = authService.login(LOGIN, null);

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo(REFRESH_TOKEN);
    }

    @Test
    void loginSavesSessionHoldingTheTokenHashNotTheToken() {
        User user = stubSuccessfulLogin();

        authService.login(LOGIN, null);

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

        authService.login(LOGIN, null);

        verify(tokenService).issueAccessToken(user.getId(), SESSION_ID, REFRESH_TOKEN.expiresAt());
    }

    @Test
    void loginRejectsWrongPassword() {
        when(userAuthRepository.findLoginCredentials("andre"))
                .thenReturn(Optional.of(new LoginCredentials(new User(), REAL_HASH)));
        when(passwordEncoder.matches("password123", REAL_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authService.login(LOGIN, null)).isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(tokenService, userSessionRepository);
    }

    @Test
    void loginStillRunsBcryptForUnknownUser() {
        when(userAuthRepository.findLoginCredentials("andre")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(LOGIN, null)).isInstanceOf(InvalidCredentialsException.class);

        // Against the dummy hash: the same BCrypt cost as a wrong password, so timing can't reveal the difference.
        verify(passwordEncoder).matches("password123", DUMMY_HASH);
        verifyNoInteractions(tokenService, userSessionRepository);
    }

    @Test
    void loginRejectsUnknownUserEvenIfTheDummyHashMatches() {
        when(userAuthRepository.findLoginCredentials("andre")).thenReturn(Optional.empty());
        when(passwordEncoder.matches("password123", DUMMY_HASH)).thenReturn(true);

        assertThatThrownBy(() -> authService.login(LOGIN, null)).isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(tokenService, userSessionRepository);
    }

    // A locked username costs no hashing, and a correct password can't get through the lock.
    @Test
    void lockedUsernameIsRejectedBeforeBcrypt() {
        doThrow(new RateLimitException(300)).when(loginLockout).consume("andre");

        assertThatThrownBy(() -> authService.login(LOGIN, null)).isInstanceOf(RateLimitException.class);

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verifyNoInteractions(userAuthRepository, tokenService, userSessionRepository);
    }

    // Keyed on the name as typed, lowercased, never the user id: an unknown name locks exactly like a real one.
    @Test
    void lockoutKeyIsTheLowercasedUsername() {
        when(userAuthRepository.findLoginCredentials("AnDrE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("AnDrE", "password123"), null))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(loginLockout).consume("andre");
    }

    @Test
    void successfulLoginClearsTheFailedAttempts() {
        stubSuccessfulLogin();

        authService.login(LOGIN, null);

        verify(loginLockout).clear("andre");
    }

    @Test
    void failedLoginKeepsItsAttemptCounted() {
        when(userAuthRepository.findLoginCredentials("andre"))
                .thenReturn(Optional.of(new LoginCredentials(new User(), REAL_HASH)));

        assertThatThrownBy(() -> authService.login(LOGIN, null)).isInstanceOf(InvalidCredentialsException.class);

        verify(loginLockout).consume("andre");
        verify(loginLockout, never()).clear(anyString());
    }

    // Revoked first, so a failure while revoking leaves no new session that no client holds.
    @Test
    void loginRevokesTheCookiesSessionBeforeStartingItsOwn() {
        stubSuccessfulLogin();
        when(tokenService.hashRefreshToken("old-token")).thenReturn("hashed-old-token");

        authService.login(LOGIN, "old-token");

        InOrder inOrder = inOrder(userSessionRepository);
        inOrder.verify(userSessionRepository).revokeByRefreshTokenHash(eq("hashed-old-token"), any(Instant.class));
        inOrder.verify(userSessionRepository).save(any(UserSession.class));
    }

    @Test
    void wrongPasswordLeavesTheCookiesSessionAlone() {
        when(userAuthRepository.findLoginCredentials("andre"))
                .thenReturn(Optional.of(new LoginCredentials(new User(), REAL_HASH)));

        assertThatThrownBy(() -> authService.login(LOGIN, "old-token")).isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(userSessionRepository);
    }

    @Test
    void loginWithoutACookieRevokesNothing() {
        stubSuccessfulLogin();

        authService.login(LOGIN, null);
        authService.login(LOGIN, " ");

        verify(userSessionRepository, never()).revokeByRefreshTokenHash(any(), any());
    }

    @Test
    void refreshRotatesTheTokenOnTheSameSessionAndKeepsItsExpiry() {
        Instant expiresAt = Instant.now().plus(Duration.ofDays(10));
        UserSession session = storedSession(expiresAt);
        RefreshToken rotated = new RefreshToken("new-token", expiresAt);
        when(tokenService.newRefreshToken(expiresAt)).thenReturn(rotated);
        when(tokenService.hashRefreshToken("new-token")).thenReturn("hashed-new-token");
        when(tokenService.issueAccessToken(session.getUserId(), SESSION_ID, expiresAt)).thenReturn("access-token");

        AuthResult result = authService.refresh("old-token");

        verify(tokenService).issueAccessToken(session.getUserId(), SESSION_ID, expiresAt);
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

    private UUID stubSuccessfulRegister() {
        UUID id = UUID.randomUUID();
        when(passwordEncoder.encode("password123")).thenReturn("{bcrypt}hashed");
        runTransactionsInline();
        // The real repository assigns the id on save; the mock has to do it by hand.
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", id);
            return user;
        });
        return id;
    }

    // What TransactionTemplate does, minus the transaction: run the callback and return its result.
    private void runTransactionsInline() {
        when(transactionOperations.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(new SimpleTransactionStatus()));
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
        when(tokenService.issueAccessToken(user.getId(), SESSION_ID, REFRESH_TOKEN.expiresAt()))
                .thenReturn("access-token");
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
        verify(tokenService, never()).issueAccessToken(any(), any(), any());
    }

}
