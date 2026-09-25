package com.euvmodcreator.auth.exception;

import com.euvmodcreator.error.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidRefreshTokenException extends ApiException {

    public InvalidRefreshTokenException() {
        super(HttpStatus.UNAUTHORIZED, "auth.invalid_refresh_token", "Invalid refresh token");
    }

}
