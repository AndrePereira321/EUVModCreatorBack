package com.euvmodcreator.auth.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.util.UUID;

class AuthenticatedUserConverter implements Converter<Jwt, AuthenticatedUserToken> {

    @Override
    public AuthenticatedUserToken convert(Jwt jwt) {
        String sessionId = jwt.getClaimAsString(TokenService.SESSION_ID_CLAIM);
        if (sessionId == null) {
            throw new InvalidBearerTokenException("Access token has no session id");
        }
        String userId = jwt.getSubject();
        if (userId == null) {
            throw new InvalidBearerTokenException("Access token has no subject");
        }
        AuthenticatedUser user = new AuthenticatedUser(UUID.fromString(userId), UUID.fromString(sessionId));
        return new AuthenticatedUserToken(user, jwt);
    }

}
