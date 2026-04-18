package com.council.config;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Creates pre-configured {@link RestClient} instances for provider adapters.
 * Centralises timeout and header setup so adapters stay focused on API specifics.
 * Uses hard socket-level timeouts so hung upstream calls do not pin virtual threads.
 */
@Component
public class RestClientFactory {

    private static final Duration HARD_READ_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration HARD_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Build a {@link RestClient} with standard timeouts and optional default headers.
     *
     * @param baseUrl        provider API base URL
     * @param timeoutSeconds provider-config timeout in seconds (kept for API compatibility)
     * @param defaultHeaders headers added to every request (may be empty)
     */
    public RestClient create(String baseUrl, int timeoutSeconds, Map<String, String> defaultHeaders) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HARD_CONNECT_TIMEOUT)
            .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(HARD_READ_TIMEOUT);

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
            .requestFactory(requestFactory);

        if (defaultHeaders != null) {
            defaultHeaders.forEach(builder::defaultHeader);
        }

        return builder.build();
    }
}

