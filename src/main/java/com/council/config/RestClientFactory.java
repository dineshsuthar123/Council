package com.council.config;

import com.council.common.CouncilConstants;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Creates pre-configured {@link RestClient} instances for provider adapters.
 * Centralises timeout and header setup so adapters stay focused on API specifics.
 */
@Component
public class RestClientFactory {

    /**
     * Build a {@link RestClient} with standard timeouts and optional default headers.
     *
     * @param baseUrl        provider API base URL
     * @param timeoutSeconds read timeout in seconds
     * @param defaultHeaders headers added to every request (may be empty)
     */
    public RestClient create(String baseUrl, int timeoutSeconds, Map<String, String> defaultHeaders) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(CouncilConstants.DEFAULT_CONNECT_TIMEOUT_SECONDS));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory);

        if (defaultHeaders != null) {
            defaultHeaders.forEach(builder::defaultHeader);
        }

        return builder.build();
    }
}

