package com.euvmodcreator.auth.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

public class AuthenticatedUserToken extends AbstractAuthenticationToken {

    private final AuthenticatedUser principal;

    private final Jwt jwt;

    public AuthenticatedUserToken(AuthenticatedUser principal, Jwt jwt) {
        super(List.of());

        this.principal = principal;
        this.jwt = jwt;

        super.setAuthenticated(true);
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return principal;
    }

    @Override
    public Jwt getCredentials() {
        return jwt;
    }
}
