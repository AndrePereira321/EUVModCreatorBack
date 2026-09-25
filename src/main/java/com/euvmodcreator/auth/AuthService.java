package com.euvmodcreator.auth;

import com.euvmodcreator.auth.dto.LoginRequest;
import com.euvmodcreator.auth.dto.RegisterRequest;
import com.euvmodcreator.auth.exception.InvalidCredentialsException;
import com.euvmodcreator.auth.exception.UsernameTakenException;
import com.euvmodcreator.auth.model.User;
import com.euvmodcreator.auth.model.UserAuth;
import com.euvmodcreator.auth.repository.LoginCredentials;
import com.euvmodcreator.auth.repository.UserAuthRepository;
import com.euvmodcreator.auth.repository.UserRepository;
import com.euvmodcreator.auth.security.TokenService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class AuthService {

    private final UserRepository userRepository;

    private final UserAuthRepository userAuthRepository;

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

    String login(LoginRequest request) {
        Optional<LoginCredentials> credentials =
                userAuthRepository.findLoginCredentials(request.username());

        if (!passwordMatches(request.password(), credentials)) {
            throw new InvalidCredentialsException();
        }

        return tokenService.issueAccessToken(credentials.orElseThrow().user());
    }
    
    private boolean passwordMatches(String password, Optional<LoginCredentials> credentials) {
        String hash = credentials.map(LoginCredentials::passwordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(password, hash);   // always runs
        return credentials.isPresent() && matches;
    }

}
