package com.euvmodcreator.auth.model;

import com.euvmodcreator.auth.security.RefreshToken;

public record AuthResult(
        String accessToken,
        RefreshToken refreshToken
) {

    @Override
    public String toString() {
        return "AuthResult[accessToken=***, refreshToken=" + refreshToken + "]";
    }

}
