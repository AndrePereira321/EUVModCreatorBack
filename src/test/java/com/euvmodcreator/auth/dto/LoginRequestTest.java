package com.euvmodcreator.auth.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    // Register's rules describe new accounts; login must not enforce them, or tightening one locks out old accounts.
    @Test
    void acceptsCredentialsThatRegisterWouldReject() {
        assertThat(failedRules(new LoginRequest("a b", "short"))).isEmpty();
        assertThat(failedRules(new LoginRequest("a".repeat(32), "short"))).isEmpty();
    }

    // The one rule login shares: no account was ever allowed a longer username, so the cap locks nobody out.
    @Test
    void rejectsUsernameLongerThanRegisterEverAllowed() {
        assertThat(failedRules(new LoginRequest("a".repeat(33), "password123"))).contains("username:Size");
    }

    @ParameterizedTest
    @CsvSource({
            "'',          password123,                 username:NotBlank",
            "'   ',       password123,                 username:NotBlank",
            "andre,       '',                          password:NotBlank",
            "andre,       €€€€€€€€€€€€€€€€€€€€€€€€€,   password:MaxBytes", // 25 characters, 75 bytes
    })
    void rejectsInvalidRequest(String username, String password, String failedRule) {
        assertThat(failedRules(new LoginRequest(username, password))).contains(failedRule);
    }

    @Test
    void toStringHidesThePassword() {
        assertThat(new LoginRequest("andre", "password123").toString()).doesNotContain("password123");
    }

    // "field:Constraint" for every failed rule, e.g. "username:NotBlank".
    private static Set<String> failedRules(LoginRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(LoginRequestTest::describe)
                .collect(Collectors.toSet());
    }

    private static String describe(ConstraintViolation<LoginRequest> violation) {
        String constraint = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        return violation.getPropertyPath() + ":" + constraint;
    }

}
