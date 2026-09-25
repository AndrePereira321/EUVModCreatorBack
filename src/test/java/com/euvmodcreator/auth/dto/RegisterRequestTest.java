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

class RegisterRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static final String VALID_USERNAME = "Andre_42";

    private static final String VALID_PASSWORD = "password123";

    @Test
    void acceptsValidRequest() {
        assertThat(failedRules(new RegisterRequest(VALID_USERNAME, VALID_PASSWORD))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "'',                                  NotBlank",
            "ab,                                  Size",
            "abcdefghijklmnopqrstuvwxyz0123456,   Size",    // 33 characters
            "a b,                                 Pattern",
            "andré,                               Pattern",
            "an-dre,                              Pattern",
    })
    void rejectsInvalidUsername(String username, String rule) {
        assertThat(failedRules(new RegisterRequest(username, VALID_PASSWORD))).contains("username:" + rule);
    }

    @ParameterizedTest
    @CsvSource({
            "'',                                  NotBlank",
            "'        ',                          NotBlank",
            "seven77,                             Size",
            "abcdefghijklmnopqrstuvwxyz0123456,   Size",     // 33 characters
            "€€€€€€€€€€€€€€€€€€€€€€€€€,           MaxBytes", // 25 characters, 75 bytes
    })
    void rejectsInvalidPassword(String password, String rule) {
        assertThat(failedRules(new RegisterRequest(VALID_USERNAME, password))).contains("password:" + rule);
    }

    @Test
    void toStringHidesThePassword() {
        assertThat(new RegisterRequest(VALID_USERNAME, VALID_PASSWORD).toString()).doesNotContain(VALID_PASSWORD);
    }

    // "field:Constraint" for every failed rule, e.g. "username:Size".
    private static Set<String> failedRules(RegisterRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(RegisterRequestTest::describe)
                .collect(Collectors.toSet());
    }

    private static String describe(ConstraintViolation<RegisterRequest> violation) {
        String constraint = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        return violation.getPropertyPath() + ":" + constraint;
    }

}
