package com.euvmodcreator.auth.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.util.UUID;

class AuthenticatedUserConverter implements Converter<Jwt, AuthenticatedUserToken> {

    @Override
    public AuthenticatedUserToken convert(Jwt jwt) {
        UUID sessionId = parseId(jwt.getClaimAsString(TokenService.SESSION_ID_CLAIM), "session id");
        UUID userId = parseId(jwt.getSubject(), "subject");
        return new AuthenticatedUserToken(new AuthenticatedUser(userId, sessionId), jwt);
    }

    private static UUID parseId(String value, String name) {
        if (value == null) {
            throw new InvalidBearerTokenException("Access token has no " + name);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("Access token's " + name + " is not a UUID", e);
        }
    }

}
