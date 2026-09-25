package com.euvmodcreator.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedUserConverterTest {

    private final AuthenticatedUserConverter converter = new AuthenticatedUserConverter();

    @Test
    void principalCarriesTheUserIdAndTheSessionId() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Jwt jwt = jwt().subject(userId.toString()).claim("sid", sessionId.toString()).build();

        AuthenticatedUserToken token = converter.convert(jwt);

        assertThat(token.getPrincipal()).isEqualTo(new AuthenticatedUser(userId, sessionId));
        assertThat(token.getCredentials()).isSameAs(jwt);
        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getAuthorities()).isEmpty();
    }

    // An AuthenticationException becomes a 401; anything else would escape the filter as a 500.
    @Test
    void rejectsTokenWithoutSessionId() {
        Jwt jwt = jwt().subject(UUID.randomUUID().toString()).build();

        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void rejectsTokenWithoutSubject() {
        Jwt jwt = jwt().claim("sid", UUID.randomUUID().toString()).build();

        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(InvalidBearerTokenException.class);
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "HS256");
    }

}
