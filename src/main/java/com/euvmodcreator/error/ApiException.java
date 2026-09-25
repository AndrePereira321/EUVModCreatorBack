package com.euvmodcreator.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Base for every error the API reports on purpose. The {@code code} is the contract with the frontend, which
 * translates it; the message is English for developers and logs, and is never shown to users.
 * <p>
 * {@code params} are values the translated message can use ({@code {"limit": 20}}). They reach the browser as
 * they are, so only put in data the user may see, and never text meant to be displayed.
 */
@Getter
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    private final String code;

    private final Map<String, Object> params;

    protected ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    protected ApiException(HttpStatus status, String code, String message, Map<String, Object> params) {
        super(message);
        this.status = status;
        this.code = code;
        this.params = Map.copyOf(params);
    }

}
