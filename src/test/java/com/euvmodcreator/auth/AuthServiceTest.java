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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final RegisterRequest REGISTER = new RegisterRequest("Andre", "password123");

    private static final LoginRequest LOGIN = new LoginRequest("andre", "password123");

    private static final String DUMMY_HASH = "{bcrypt}dummy";

    private static final String REAL_HASH = "{bcrypt}real";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAuthRepository userAuthRepository;

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
    void loginIssuesTokenForMatchingPassword() {
        User user = new User();
        when(userAuthRepository.findLoginCredentials("andre"))
                .thenReturn(Optional.of(new LoginCredentials(user, REAL_HASH)));
        when(passwordEncoder.matches("password123", REAL_HASH)).thenReturn(true);
        when(tokenService.issueAccessToken(user)).thenReturn("token");

        assertThat(authService.login(LOGIN)).isEqualTo("token");
    }

    @Test
    void loginRejectsWrongPassword() {
        when(userAuthRepository.findLoginCredentials("andre"))
                .thenReturn(Optional.of(new LoginCredentials(new User(), REAL_HASH)));
        when(passwordEncoder.matches("password123", REAL_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authService.login(LOGIN)).isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(tokenService);
    }

    @Test
    void loginStillRunsBcryptForUnknownUser() {
        when(userAuthRepository.findLoginCredentials("andre")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(LOGIN)).isInstanceOf(InvalidCredentialsException.class);

        // Against the dummy hash: the same BCrypt cost as a wrong password, so timing can't reveal the difference.
        verify(passwordEncoder).matches("password123", DUMMY_HASH);
        verifyNoInteractions(tokenService);
    }

    @Test
    void loginRejectsUnknownUserEvenIfTheDummyHashMatches() {
        when(userAuthRepository.findLoginCredentials("andre")).thenReturn(Optional.empty());
        when(passwordEncoder.matches("password123", DUMMY_HASH)).thenReturn(true);

        assertThatThrownBy(() -> authService.login(LOGIN)).isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(tokenService);
    }

}
