package com.euvmodcreator.auth.exception;

import com.euvmodcreator.error.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidAccessTokenException extends ApiException {

    public InvalidAccessTokenException() {
        super(HttpStatus.UNAUTHORIZED, "auth.invalid_access_token", "Missing or invalid access token");
    }

}
