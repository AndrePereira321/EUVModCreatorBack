package com.euvmodcreator.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsPropertiesTest {

    @Test
    void readsACommaSeparatedList() {
        assertThat(bind(Map.of("web.cors.allowed-origins", "http://localhost:5173,https://app.example.com"))
                .allowedOrigins())
                .containsExactly("http://localhost:5173", "https://app.example.com");
    }

    @Test
    void allowsNoOriginWhenUnset() {
        assertThat(bind(Map.of()).allowedOrigins()).isEmpty();
    }

    // Production sets CORS_ALLOWED_ORIGINS empty when the frontend shares the API's origin.
    @Test
    void allowsNoOriginWhenEmpty() {
        assertThat(bind(Map.of("web.cors.allowed-origins", "")).allowedOrigins()).isEmpty();
    }

    // Browsers refuse a * together with credentials; failing at startup beats failing on the first request.
    @Test
    void refusesWildcard() {
        assertThatThrownBy(() -> new CorsProperties(List.of("*"))).isInstanceOf(IllegalArgumentException.class);
    }

    private static CorsProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("web.cors", CorsProperties.class);
    }

}
