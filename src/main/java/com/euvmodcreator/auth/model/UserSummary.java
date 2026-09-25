package com.euvmodcreator.auth.model;

import com.euvmodcreator.auth.entity.User;

import java.util.UUID;

public record UserSummary(UUID id, String username) {

    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getUsername());
    }

}
