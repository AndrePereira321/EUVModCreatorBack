package com.euvmodcreator.auth;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("auth.session-cleanup")
record SessionCleanupProperties(

        @NotBlank
        @DefaultValue("0 0 5 * * *")
        String cron,

        @DurationMin(seconds = 0)
        @DefaultValue("30d")
        Duration retention

) {
}
