package com.euvmodcreator.auth;

import com.euvmodcreator.auth.dto.RegisterRequest;
import com.euvmodcreator.auth.model.User;
import com.euvmodcreator.auth.model.UserAuth;
import com.euvmodcreator.auth.repository.UserAuthRepository;
import com.euvmodcreator.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final RegisterRequest REQUEST = new RegisterRequest("Andre", "password123");

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAuthRepository userAuthRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    @Test
    void rejectsTakenUsernameWithoutSavingAnything() {
        when(userRepository.existsByUsernameIgnoreCase("Andre")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(REQUEST)).isInstanceOf(UsernameTakenException.class);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(userAuthRepository, passwordEncoder);
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

        User registered = authService.register(REQUEST);

        assertThat(registered.getId()).isEqualTo(id);
        assertThat(registered.getUsername()).isEqualTo("Andre");

        ArgumentCaptor<UserAuth> savedAuth = ArgumentCaptor.forClass(UserAuth.class);
        verify(userAuthRepository).save(savedAuth.capture());
        assertThat(savedAuth.getValue().getUserId()).isEqualTo(id);
        assertThat(savedAuth.getValue().getPasswordHash()).isEqualTo("{bcrypt}hashed");
    }

}
