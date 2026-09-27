package com.euvmodcreator.auth.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginResponseTest {

    @Test
    void toStringHidesTheAccessToken() {
        assertThat(new LoginResponse("access-secret").toString()).doesNotContain("access-secret");
    }

}
