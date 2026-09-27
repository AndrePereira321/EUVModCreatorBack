package com.euvmodcreator.auth;

import com.euvmodcreator.ratelimit.Lockout;
import com.euvmodcreator.ratelimit.RateLimitInterceptor;
import com.euvmodcreator.ratelimit.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@RequiredArgsConstructor
class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimitProperties rateLimitProperties;

    @Bean
    RateLimiter loginRateLimiter() {
        return new RateLimiter(rateLimitProperties.loginCapacity(), rateLimitProperties.loginPeriod());
    }

    @Bean
    RateLimiter registerRateLimiter() {
        return new RateLimiter(rateLimitProperties.registerCapacity(), rateLimitProperties.registerPeriod());
    }

    @Bean
    Lockout loginLockout() {
        return new Lockout(rateLimitProperties.maxFailedLogins(), rateLimitProperties.failedLoginLockDuration());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(loginRateLimiter()))
                .addPathPatterns("/api/auth/login");
        registry.addInterceptor(new RateLimitInterceptor(registerRateLimiter()))
                .addPathPatterns("/api/auth/register");
    }
}
