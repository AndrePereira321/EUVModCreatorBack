package com.euvmodcreator.auth.result;

import com.euvmodcreator.auth.security.RefreshToken;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LoginResultTest {

    @Test
    void toStringHidesBothTokens() {
        LoginResult result = new LoginResult("access-secret", new RefreshToken("refresh-secret", Instant.now()));

        assertThat(result.toString()).doesNotContain("access-secret", "refresh-secret");
    }

}
