package com.euvmodcreator.auth.model;

import com.euvmodcreator.auth.entity.User;

import java.util.UUID;

public record UserSummary(
        UUID id,
        String username,
        String displayName,
        String bio,
        String steamUrl,
        String paradoxForumUrl,
        String discordUrl
) {

    public static UserSummary from(User user) {
        return new UserSummary(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getBio(),
                user.getSteamUrl(),
                user.getParadoxForumUrl(),
                user.getDiscordUrl());
    }

}
