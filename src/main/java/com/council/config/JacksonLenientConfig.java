package com.council.config;

import com.fasterxml.jackson.core.JsonParser;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lenient Jackson parser configuration for noisy LLM JSON payloads.
 */
@Configuration
public class JacksonLenientConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer lenientJsonParserCustomizer() {
        return builder -> builder.featuresToEnable(
                JsonParser.Feature.ALLOW_MISSING_VALUES,
                JsonParser.Feature.ALLOW_SINGLE_QUOTES,
                JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES
        );
    }
}