package com.euvmodcreator.auth.dto;

import com.euvmodcreator.auth.model.User;

import java.util.UUID;

public record RegisterResponse(UUID id, String username) {

    public static RegisterResponse from(User user) {
        return new RegisterResponse(user.getId(), user.getUsername());
    }

}
