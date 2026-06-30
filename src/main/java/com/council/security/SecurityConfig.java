package com.council.security;

import com.council.api.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;
import java.util.UUID;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private static final String ADMIN_ROLE = "ADMIN";

    private static final RequestMatcher KNOWN_ADMIN_ENDPOINTS = new OrRequestMatcher(
            AntPathRequestMatcher.antMatcher("/api/v1/traces"),
            AntPathRequestMatcher.antMatcher("/api/v1/traces/**"),
            AntPathRequestMatcher.antMatcher("/api/v1/providers/status"),
            AntPathRequestMatcher.antMatcher("/api/v1/providers/preflight"),
            AntPathRequestMatcher.antMatcher("/api/v1/providers/scorecards"),
            AntPathRequestMatcher.antMatcher("/api/v1/providers/*/reset-cooldown"),
            AntPathRequestMatcher.antMatcher("/api/v1/metrics"),
            AntPathRequestMatcher.antMatcher("/api/v1/design/**"),
            AntPathRequestMatcher.antMatcher("/api/v1/evaluate"),
            AntPathRequestMatcher.antMatcher("/api/v1/evaluations"),
            AntPathRequestMatcher.antMatcher("/api/v1/evaluations/**"),
            AntPathRequestMatcher.antMatcher("/actuator"),
            AntPathRequestMatcher.antMatcher("/actuator/**"),
            AntPathRequestMatcher.antMatcher("/v3/api-docs"),
            AntPathRequestMatcher.antMatcher("/v3/api-docs/**"),
            AntPathRequestMatcher.antMatcher("/swagger-ui.html"),
            AntPathRequestMatcher.antMatcher("/swagger-ui/**")
    );

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                        .requestMatchers("/", "/index.html", "/app.js", "/styles.css", "/favicon.ico").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/reason").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/reason/runs").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/reason/runs/*/events").permitAll()
                        .requestMatchers(KNOWN_ADMIN_ENDPOINTS).hasRole(ADMIN_ROLE)
                        .anyRequest().authenticated())
                .build();
    }

    @Bean
    UserDetailsService adminUserDetailsService(
            @Value("${council.security.admin-username:admin}") String username,
            @Value("${council.security.admin-password:}") String configuredPassword,
            PasswordEncoder passwordEncoder) {
        String password = configuredPassword == null || configuredPassword.isBlank()
                ? UUID.randomUUID().toString()
                : configuredPassword;
        if (configuredPassword == null || configuredPassword.isBlank()) {
            log.warn("COUNCIL_ADMIN_PASSWORD is not set; admin endpoints require an in-memory generated password for this process.");
        }

        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles(ADMIN_ROLE)
                .build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            if (isUnknownApiPath(request)) {
                writeJson(response, HttpStatus.NOT_FOUND,
                        ErrorResponse.of("NOT_FOUND", "No endpoint found for " + request.getMethod()
                                + " " + request.getRequestURI()));
                return;
            }

            response.setHeader("WWW-Authenticate", "Basic realm=\"Council Admin\"");
            writeJson(response, HttpStatus.UNAUTHORIZED,
                    ErrorResponse.of("UNAUTHORIZED", "Authentication is required for this endpoint."));
        };
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> writeJson(response, HttpStatus.FORBIDDEN,
                ErrorResponse.of("FORBIDDEN", "Admin privileges are required for this endpoint."));
    }

    private boolean isUnknownApiPath(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/")
                && !KNOWN_ADMIN_ENDPOINTS.matches(request);
    }

    private void writeJson(HttpServletResponse response,
                           HttpStatus status,
                           ErrorResponse errorResponse) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}
