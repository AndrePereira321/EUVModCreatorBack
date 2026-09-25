package com.euvmodcreator.auth;

import com.euvmodcreator.error.ApiException;
import org.springframework.http.HttpStatus;

class UsernameTakenException extends ApiException {

    UsernameTakenException() {
        super(HttpStatus.CONFLICT, "auth.username_taken", "Username is already taken");
    }

}
