package com.euvmodcreator.error;

import jakarta.validation.ConstraintViolation;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns every exception that leaves a controller into an RFC 9457 Problem Details body carrying a {@code code}.
 * The parent class already converts Spring MVC's own exceptions (malformed JSON, wrong method, unsupported media
 * type); this class adds the code to those and handles the application's own.
 */
@Slf4j
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String CODE = "code";

    // Constraint attributes that are Bean Validation plumbing, or would leak implementation detail to the client.
    private static final Set<String> HIDDEN_ATTRIBUTES = Set.of("message", "groups", "payload", "regexp", "flags");

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException ex) {
        ProblemDetail problem = problem(ex.getStatus(), ex.getCode(), ex.getMessage());
        if (!ex.getParams().isEmpty()) {
            problem.setProperty("params", ex.getParams());
        }
        return problem;
    }

    // The unique constraint catching what a service check missed, e.g. two registrations racing for one username.
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Database constraint rejected a write", ex);
        return problem(HttpStatus.CONFLICT, "conflict", "The request conflicts with existing data");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) throws Exception {
        // Leave these to Spring Security, which answers 401/403 itself.
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error");
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        List<ApiFieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toApiFieldError)
                .toList();

        ProblemDetail body = ex.getBody();
        body.setProperty(CODE, "validation_failed");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    // Every response the parent class builds passes through here, so its errors get a code too.
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

        if (body instanceof ProblemDetail problem && !hasCode(problem)) {
            problem.setProperty(CODE, codeFor(statusCode));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty(CODE, code);
        return problem;
    }

    private static boolean hasCode(ProblemDetail problem) {
        return problem.getProperties() != null && problem.getProperties().containsKey(CODE);
    }

    // 415 becomes "unsupported_media_type", 404 "not_found".
    private static String codeFor(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.name().toLowerCase(Locale.ROOT) : "http_" + statusCode.value();
    }

    private static ApiFieldError toApiFieldError(FieldError error) {
        return new ApiFieldError(error.getField(), error.getCode(), constraintParams(error));
    }

    private static Map<String, Object> constraintParams(FieldError error) {
        if (!error.contains(ConstraintViolation.class)) {
            return Map.of();
        }
        Map<String, Object> attributes = error.unwrap(ConstraintViolation.class)
                .getConstraintDescriptor()
                .getAttributes();

        Map<String, Object> params = new TreeMap<>();
        attributes.forEach((name, value) -> {
            boolean simpleValue = value instanceof Number || value instanceof Boolean || value instanceof String;
            if (simpleValue && !HIDDEN_ATTRIBUTES.contains(name)) {
                params.put(name, value);
            }
        });
        return params;
    }

}
