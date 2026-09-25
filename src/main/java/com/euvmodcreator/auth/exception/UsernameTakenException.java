package com.euvmodcreator.auth.exception;

import com.euvmodcreator.error.ApiException;
import org.springframework.http.HttpStatus;

public class UsernameTakenException extends ApiException {

    public UsernameTakenException() {
        super(HttpStatus.CONFLICT, "auth.username_taken", "Username is already taken");
    }

}
