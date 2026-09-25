package com.euvmodcreator.auth.result;

import com.euvmodcreator.auth.security.RefreshToken;

public record LoginResult(
        String accessToken,
        RefreshToken refreshToken
) {

    @Override
    public String toString() {
        return "LoginResult[accessToken=***, refreshToken=" + refreshToken + "]";
    }

}
