package com.euvmodcreator.auth.dto;

import com.euvmodcreator.auth.model.UserSummary;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String username,
        String displayName,
        String bio,
        String steamUrl,
        String paradoxForumUrl,
        String discordUrl
) {

    public static UserResponse from(UserSummary user) {
        return new UserResponse(
                user.id(),
                user.username(),
                user.displayName(),
                user.bio(),
                user.steamUrl(),
                user.paradoxForumUrl(),
                user.discordUrl());
    }
}