package com.euvmodcreator.auth.repository;

import com.euvmodcreator.auth.model.User;

public record LoginCredentials(
        User user,
        String passwordHash
) {

    @Override
    public String toString() {
        return "LoginCredentials[userId=" + user.getId() + ", passwordHash=***]";
    }
}
