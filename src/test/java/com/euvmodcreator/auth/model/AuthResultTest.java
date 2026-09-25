package com.euvmodcreator.auth.model;

import com.euvmodcreator.auth.security.RefreshToken;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuthResultTest {

    @Test
    void toStringHidesBothTokens() {
        AuthResult result = new AuthResult("access-secret", new RefreshToken("refresh-secret", Instant.now()));

        assertThat(result.toString()).doesNotContain("access-secret", "refresh-secret");
    }

}
