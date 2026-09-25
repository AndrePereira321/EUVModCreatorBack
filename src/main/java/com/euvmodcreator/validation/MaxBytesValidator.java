package com.euvmodcreator.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

// Public because Hibernate Validator, outside Spring, instantiates validators through their public constructor.
public class MaxBytesValidator implements ConstraintValidator<MaxBytes, CharSequence> {

    private int maxBytes;

    @Override
    public void initialize(MaxBytes annotation) {
        this.maxBytes = annotation.value();
    }

    // null is valid, as with every built-in constraint; @NotNull or @NotBlank rejects it.
    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || value.toString().getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }

}
