package com.euvmodcreator.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Over HTTP every test request comes from 127.0.0.1, so only here can two clients be told apart.
class RateLimitInterceptorTest {

    private static final String CLIENT = "203.0.113.7";

    private static final String OTHER_CLIENT = "203.0.113.8";

    private final RateLimitInterceptor interceptor = new RateLimitInterceptor(new RateLimiter(1, Duration.ofHours(1)));

    @Test
    void oneClientUsingUpItsLimitDoesNotLimitAnother() {
        handle(requestFrom(CLIENT));

        assertThat(handle(requestFrom(OTHER_CLIENT))).isTrue();
    }

    // The client writes X-Forwarded-For, so a new one per request would get a fresh bucket each time.
    @Test
    void forgedForwardedForHeaderDoesNotResetTheLimit() {
        MockHttpServletRequest first = requestFrom(CLIENT);
        first.addHeader("X-Forwarded-For", "198.51.100.1");
        handle(first);

        MockHttpServletRequest second = requestFrom(CLIENT);
        second.addHeader("X-Forwarded-For", "198.51.100.2");

        assertThatThrownBy(() -> handle(second)).isInstanceOf(RateLimitException.class);
    }

    private boolean handle(MockHttpServletRequest request) {
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    private static MockHttpServletRequest requestFrom(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(address);
        return request;
    }

}
