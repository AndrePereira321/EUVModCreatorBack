package com.euvmodcreator.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MaxBytesTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    record Sample(@MaxBytes(4) String value) {
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "abcd", "éé"}) // "éé" is 2 characters but exactly 4 bytes
    void acceptsUpToTheLimitInBytes(String value) {
        assertThat(VALIDATOR.validate(new Sample(value))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcde", "ééé", "€€"}) // 3 characters but 6 bytes, and "€" takes 3 bytes on its own
    void rejectsMoreBytesThanTheLimit(String value) {
        assertThat(VALIDATOR.validate(new Sample(value))).hasSize(1);
    }

}
