package com.euvmodcreator.auth.exception;

import com.euvmodcreator.error.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "auth.invalid_credentials", "Invalid username or password");
    }

}
