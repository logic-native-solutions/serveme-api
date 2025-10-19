package com.logicnativesolution.servemeapi.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Centralized Jackson configuration so that both WebMVC/WebFlux and any injected ObjectMapper
 * handle java.time.* types (Instant, LocalDateTime, etc.) as ISO-8601 strings.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer javaTimeCustomizer() {
        return builder -> {
            builder.modules(new JavaTimeModule());
            // Prefer ISO-8601 instead of timestamps
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            // Be lenient with unknowns, useful during mobile<->API iterations
            builder.featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            // Ensure handler requirement won't break if module is present
            builder.featuresToDisable(MapperFeature.REQUIRE_SETTERS_FOR_GETTERS);
            builder.serializationInclusion(JsonInclude.Include.NON_NULL);
        };
    }
}
