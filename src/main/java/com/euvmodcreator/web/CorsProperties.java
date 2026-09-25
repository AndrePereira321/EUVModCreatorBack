package com.euvmodcreator.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

import java.util.List;

@ConfigurationProperties("web.cors")
record CorsProperties(
        @DefaultValue
        List<String> allowedOrigins
) {

    CorsProperties {
        Assert.isTrue(!allowedOrigins.contains("*"),
                "web.cors.allowed-origins can't be *: requests carry the refresh cookie, so origins must be explicit");
    }

}
