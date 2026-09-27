package com.euvmodcreator.auth;

import com.euvmodcreator.auth.dto.LoginRequest;
import com.euvmodcreator.auth.dto.RegisterRequest;
import com.euvmodcreator.auth.entity.User;
import com.euvmodcreator.auth.exception.InvalidCredentialsException;
import com.euvmodcreator.auth.repository.LoginCredentials;
import com.euvmodcreator.auth.repository.UserAuthRepository;
import com.euvmodcreator.auth.repository.UserRepository;
import com.euvmodcreator.auth.repository.UserSessionRepository;
import com.euvmodcreator.auth.security.TokenService;
import com.euvmodcreator.ratelimit.Lockout;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// The real AuthService behind the proxy @EnableResilientMethods builds, with mocked dependencies and a password
// encoder that holds every hash until the test releases it, so the calls in flight can be counted.
class AuthConcurrencyLimitTest {

    private static final String PASSWORD = "password123";

    private static final LoginRequest LOGIN = new LoginRequest("andre", PASSWORD);

    private static final RegisterRequest REGISTER = new RegisterRequest("andre", PASSWORD);

    private final Semaphore hashesStarted = new Semaphore(0);

    private final CountDownLatch release = new CountDownLatch(1);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ResilienceConfig.class)
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(UserAuthRepository.class, this::userAuthRepository)
            .withBean(UserSessionRepository.class, () -> mock(UserSessionRepository.class))
            .withBean(PasswordEncoder.class, this::holdingPasswordEncoder)
            .withBean(TokenService.class, () -> mock(TokenService.class))
            .withBean(TransactionOperations.class, this::transactionOperations)
            .withBean(Lockout.class, () -> mock(Lockout.class))
            .withBean(AuthService.class);

    @Test
    void seventeenthLoginAtOnceIsRejected() {
        contextRunner.run(context -> {
            AuthService authService = context.getBean(AuthService.class);

            whileHashing(16, () -> authService.login(LOGIN, null), () ->
                    assertThatThrownBy(() -> authService.login(LOGIN, null))
                            .isInstanceOf(InvocationRejectedException.class));

            // The sixteen finished, so their slots are free again.
            assertThatThrownBy(() -> authService.login(LOGIN, null)).isInstanceOf(InvalidCredentialsException.class);
        });
    }

    @Test
    void thirdRegistrationAtOnceIsRejected() {
        contextRunner.run(context -> {
            AuthService authService = context.getBean(AuthService.class);

            whileHashing(2, () -> authService.register(REGISTER), () ->
                    assertThatThrownBy(() -> authService.register(REGISTER))
                            .isInstanceOf(InvocationRejectedException.class));
        });
    }

    // Each method has its own throttle: logins using every slot don't turn a registration away.
    @Test
    void loginsAndRegistrationsAreLimitedSeparately() {
        contextRunner.run(context -> {
            AuthService authService = context.getBean(AuthService.class);

            whileHashing(16, () -> authService.login(LOGIN, null), () ->
                    assertThatNoException().isThrownBy(
                            () -> authService.register(new RegisterRequest("bruno", "not-held-back"))));
        });
    }

    @Test
    void registerLimitComesFromItsProperty() {
        contextRunner.withPropertyValues("euv-app.auth.rate-limit.register-concurrency=1")
                .run(context -> {
                    AuthService authService = context.getBean(AuthService.class);

                    whileHashing(1, () -> authService.register(REGISTER), () ->
                            assertThatThrownBy(() -> authService.register(REGISTER))
                                    .isInstanceOf(InvocationRejectedException.class));
                });
    }

    @Test
    void loginLimitComesFromItsProperty() {
        contextRunner.withPropertyValues("euv-app.auth.rate-limit.login-concurrency=1")
                .run(context -> {
                    AuthService authService = context.getBean(AuthService.class);

                    whileHashing(1, () -> authService.login(LOGIN, null), () ->
                            assertThatThrownBy(() -> authService.login(LOGIN, null))
                                    .isInstanceOf(InvocationRejectedException.class));
                });
    }

    // Starts the calls, waits until each is inside its hash, runs the check, then lets them all finish.
    private void whileHashing(int calls, Runnable call, Runnable check) throws InterruptedException {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                for (int i = 0; i < calls; i++) {
                    executor.submit(call);
                }
                assertThat(hashesStarted.tryAcquire(calls, 5, TimeUnit.SECONDS)).isTrue();
                check.run();
            } finally {
                release.countDown();
            }
        }
    }

    private UserAuthRepository userAuthRepository() {
        UserAuthRepository repository = mock(UserAuthRepository.class);
        when(repository.findLoginCredentials("andre"))
                .thenReturn(Optional.of(new LoginCredentials(new User(), "{bcrypt}stored")));
        return repository;
    }

    // Like TransactionTemplate after register's callback: the saved user comes back.
    private TransactionOperations transactionOperations() {
        TransactionOperations transactionOperations = mock(TransactionOperations.class);
        when(transactionOperations.execute(any())).thenAnswer(invocation -> new User());
        return transactionOperations;
    }

    private PasswordEncoder holdingPasswordEncoder() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(PASSWORD)).thenAnswer(invocation -> held("{bcrypt}new"));
        when(encoder.matches(PASSWORD, "{bcrypt}stored")).thenAnswer(invocation -> held(false));
        return encoder;
    }

    private <T> T held(T result) throws InterruptedException {
        hashesStarted.release();
        release.await(5, TimeUnit.SECONDS);
        return result;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableResilientMethods
    static class ResilienceConfig {
    }

}
