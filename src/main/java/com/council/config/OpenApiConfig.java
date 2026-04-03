package com.council.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 3.0 documentation configuration.
 * Swagger UI available at /swagger-ui.html
 * OpenAPI spec at /v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI councilOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Council API")
                        .description("Multi-model reasoning orchestration engine. "
                                + "Sends queries to multiple LLM providers in parallel, "
                                + "critically evaluates responses, and selects the best answer "
                                + "using a deterministic scoring algorithm.")
                        .version("0.1.0"))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local dev")
                ));
    }
}

