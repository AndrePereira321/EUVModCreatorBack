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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class AuthService {

    private final UserRepository userRepository;

    private final UserAuthRepository userAuthRepository;

    private final UserSessionRepository userSessionRepository;

    private final PasswordEncoder passwordEncoder;

    private final TokenService tokenService;

    private String dummyHash;

    @PostConstruct
    void init() {
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    User register(RegisterRequest registerRequest) {
        boolean userExists = userRepository.existsByUsernameIgnoreCase(registerRequest.username());
        if (userExists) {
            throw new UsernameTakenException();
        }

        User user = new User();
        user.setUsername(registerRequest.username());
        User savedUser = userRepository.save(user);

        UserAuth userAuth = new UserAuth();
        userAuth.setPasswordHash(passwordEncoder.encode(registerRequest.password()));
        userAuth.setUserId(savedUser.getId());
        userAuthRepository.save(userAuth);

        return savedUser;
    }

    AuthResult login(LoginRequest request) {
        Optional<LoginCredentials> credentials =
                userAuthRepository.findLoginCredentials(request.username());

        if (!passwordMatches(request.password(), credentials)) {
            throw new InvalidCredentialsException();
        }

        User user = credentials.orElseThrow().user();
        RefreshToken refreshToken = tokenService.newRefreshToken();
        UserSession userSession = createNewUserSession(user.getId(), refreshToken);

        return new AuthResult(tokenService.issueAccessToken(user.getId(), userSession.getId()), refreshToken);
    }

    @Transactional
    AuthResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }

        UserSession userSession = userSessionRepository
                .findByRefreshTokenHash(tokenService.hashRefreshToken(refreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (userSession.getRevokedAt() != null) {
            throw new InvalidRefreshTokenException();
        }

        Instant now = Instant.now();
        if (!userSession.getExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException();
        }

        RefreshToken newRefreshToken = tokenService.newRefreshToken(userSession.getExpiresAt());
        userSession.setRefreshTokenHash(tokenService.hashRefreshToken(newRefreshToken.value()));

        return new AuthResult(tokenService.issueAccessToken(userSession.getUserId(), userSession.getId()), newRefreshToken);
    }

    @Transactional
    void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        userSessionRepository.findByRefreshTokenHash(tokenService.hashRefreshToken(refreshToken))
                .filter(userSession -> userSession.getRevokedAt() == null)
                .ifPresent(userSession -> userSession.setRevokedAt(Instant.now()));
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

}
