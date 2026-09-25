package com.euvmodcreator.error;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Runs the handler inside MockMvc with a controller that only throws: the error cases that are hard to trigger
// through the real endpoints, without starting the app.
class GlobalExceptionHandlerTest {

    private final MockMvcTester mvc = MockMvcTester.of(
            List.of(new ThrowingController()),
            builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());

    @Test
    void apiExceptionBecomesProblemWithItsStatusAndCode() {
        assertThat(mvc.get().uri("/plain"))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .doesNotHavePath("$.params")
                .extractingPath("$.code").isEqualTo("test.failure");
    }

    @Test
    void apiExceptionParamsAreSentToTheClient() {
        assertThat(mvc.get().uri("/with-params"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.params.limit").isEqualTo(20);
    }

    @Test
    void constraintViolationFromTheDatabaseIsConflict() {
        assertThat(mvc.get().uri("/constraint"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("conflict");
    }

    @Test
    void unexpectedExceptionIs500WithoutItsMessage() {
        assertThat(mvc.get().uri("/unexpected"))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .bodyJson()
                .satisfies(body -> assertThat(body.getJson()).doesNotContain("internal detail"))
                .extractingPath("$.code").isEqualTo("internal_error");
    }

    @Test
    void springMvcErrorGetsACodeFromItsStatus() {
        assertThat(mvc.post().uri("/plain"))
                .hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
                .bodyJson()
                .extractingPath("$.code").isEqualTo("method_not_allowed");
    }

    static class TestException extends ApiException {

        TestException(Map<String, Object> params) {
            super(HttpStatus.CONFLICT, "test.failure", "Test failure", params);
        }

    }

    @RestController
    static class ThrowingController {

        @GetMapping("/plain")
        void plain() {
            throw new TestException(Map.of());
        }

        @GetMapping("/with-params")
        void withParams() {
            throw new TestException(Map.of("limit", 20));
        }

        @GetMapping("/constraint")
        void constraint() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint");
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("internal detail");
        }

    }

}
