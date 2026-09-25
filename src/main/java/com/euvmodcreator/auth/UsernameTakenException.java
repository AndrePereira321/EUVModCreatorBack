package com.euvmodcreator.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
class UsernameTakenException extends RuntimeException {
    UsernameTakenException(String message) {
        super(message);
    }
}
