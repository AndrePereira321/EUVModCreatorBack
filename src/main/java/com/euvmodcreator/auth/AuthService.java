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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.ConcurrencyLimit.ThrottlePolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class AuthService {

    private final UserRepository userRepository;

    private final UserAuthRepository userAuthRepository;

    private final UserSessionRepository userSessionRepository;

    private final PasswordEncoder passwordEncoder;

    private final TokenService tokenService;

    private final TransactionOperations transactionOperations;

    private final Lockout loginLockout;

    private String dummyHash;

    @PostConstruct
    void init() {
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @ConcurrencyLimit(limitString = RateLimitProperties.REGISTER_CONCURRENCY_LIMIT, policy = ThrottlePolicy.REJECT)
    User register(RegisterRequest registerRequest) {
        boolean userExists = userRepository.usernameExists(registerRequest.username());
        if (userExists) {
            throw new UsernameTakenException();
        }

        String passwordHash = passwordEncoder.encode(registerRequest.password());

        User user = transactionOperations.execute(status -> saveNewUser(registerRequest.username(), passwordHash));
        log.info("User {} registered", user.getId());
        return user;
    }

    @ConcurrencyLimit(limitString = RateLimitProperties.LOGIN_CONCURRENCY_LIMIT, policy = ThrottlePolicy.REJECT)
    AuthResult login(LoginRequest request, String oldRefreshToken) {
        String lockoutKey = request.username().toLowerCase(Locale.ROOT);
        int attemptsLeft = loginLockout.consume(lockoutKey);

        Optional<LoginCredentials> credentials =
                userAuthRepository.findLoginCredentials(request.username());

        if (!passwordMatches(request.password(), credentials)) {
            logFailedLogin(credentials, attemptsLeft);
            throw new InvalidCredentialsException();
        }

        loginLockout.clear(lockoutKey);
        revokeOldSession(oldRefreshToken);

        User user = credentials.orElseThrow().user();
        RefreshToken refreshToken = tokenService.newRefreshToken();
        UserSession userSession = createNewUserSession(user.getId(), refreshToken);

        String accessToken = tokenService.issueAccessToken(
                user.getId(), userSession.getId(), userSession.getExpiresAt()
        );
        log.info("User {} logged in, session {}", user.getId(), userSession.getId());
        return new AuthResult(accessToken, refreshToken);
    }

    @Transactional
    AuthResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            log.debug("Refresh refused: no refresh token cookie");
            throw new InvalidRefreshTokenException();
        }

        UserSession userSession = userSessionRepository
                .findByRefreshTokenHash(tokenService.hashRefreshToken(refreshToken))
                .orElseThrow(() -> {
                    log.debug("Refresh refused: unknown refresh token");
                    return new InvalidRefreshTokenException();
                });

        if (userSession.getRevokedAt() != null) {
            log.debug("Refresh refused: session {} of user {} is revoked", userSession.getId(), userSession.getUserId());
            throw new InvalidRefreshTokenException();
        }

        Instant now = Instant.now();
        if (!userSession.getExpiresAt().isAfter(now)) {
            log.debug("Refresh refused: session {} of user {} has expired", userSession.getId(), userSession.getUserId());
            throw new InvalidRefreshTokenException();
        }

        RefreshToken newRefreshToken = tokenService.newRefreshToken(userSession.getExpiresAt());
        userSession.setRefreshTokenHash(tokenService.hashRefreshToken(newRefreshToken.value()));

        String accessToken = tokenService.issueAccessToken(
                userSession.getUserId(), userSession.getId(), userSession.getExpiresAt()
        );
        log.debug("Session {} of user {} refreshed", userSession.getId(), userSession.getUserId());
        return new AuthResult(accessToken, newRefreshToken);
    }

    @Transactional
    void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            log.debug("Logout without a refresh token cookie");
            return;
        }

        userSessionRepository.findByRefreshTokenHash(tokenService.hashRefreshToken(refreshToken))
                .filter(userSession -> userSession.getRevokedAt() == null)
                .ifPresentOrElse(userSession -> {
                    userSession.setRevokedAt(Instant.now());
                    log.info("User {} logged out, session {}", userSession.getUserId(), userSession.getId());
                }, () -> log.debug("Logout found no live session for its refresh token"));
    }

    private User saveNewUser(String username, String passwordHash) {
        User user = new User();
        user.setUsername(username);
        User savedUser;
        try {
            savedUser = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            log.debug("A constraint refused the new user, answered as a taken username", e);
            throw new UsernameTakenException();
        }

        UserAuth userAuth = new UserAuth();
        userAuth.setPasswordHash(passwordHash);
        userAuth.setUserId(savedUser.getId());
        userAuthRepository.save(userAuth);

        return savedUser;
    }

    private UserSession createNewUserSession(UUID userId, RefreshToken refreshToken) {
        UserSession userSession = new UserSession();

        userSession.setUserId(userId);
        userSession.setRefreshTokenHash(tokenService.hashRefreshToken(refreshToken.value()));
        userSession.setExpiresAt(refreshToken.expiresAt());

        return userSessionRepository.save(userSession);
    }

    private boolean passwordMatches(String password, Optional<LoginCredentials> credentials) {
        String hash = credentials.map(LoginCredentials::passwordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(password, hash);   // always runs
        return credentials.isPresent() && matches;
    }

    private static void logFailedLogin(Optional<LoginCredentials> credentials, int attemptsLeft) {
        String who = credentials.map(c -> "user " + c.user().getId()).orElse("an unknown username");
        if (attemptsLeft > 0) {
            log.info("Login failed for {}", who);
        } else {
            log.warn("Login failed for {}, which is now locked", who);
        }
    }

    private void revokeOldSession(String oldRefreshToken) {
        if (oldRefreshToken == null || oldRefreshToken.isBlank()) {
            return;
        }

        int revoked = userSessionRepository.revokeByRefreshTokenHash(
                tokenService.hashRefreshToken(oldRefreshToken), Instant.now()
        );
        if (revoked > 0) {
            log.debug("Revoked the session of the refresh token cookie this login replaces");
        }
    }

}
