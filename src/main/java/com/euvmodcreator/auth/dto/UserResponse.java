package com.euvmodcreator.auth.dto;

import com.euvmodcreator.auth.model.UserSummary;

import java.util.UUID;

public record UserResponse(UUID id, String username) {

    public static UserResponse from(UserSummary user) {
        return new UserResponse(user.id(), user.username());
    }
}