package com.euvmodcreator.auth.dto;

public record LoginResponse(
        String accessToken
) {

    @Override
    public String toString() {
        return "LoginResponse[accessToken=***]";
    }

}
