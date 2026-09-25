package com.euvmodcreator.error;

import java.util.Map;

/**
 * One failed validation rule. {@code code} is the constraint's name ({@code Size}, {@code NotBlank}) and
 * {@code params} its attributes ({@code min}, {@code max}), so the frontend can build a translated message.
 */
record ApiFieldError(String field, String code, Map<String, Object> params) {
}
