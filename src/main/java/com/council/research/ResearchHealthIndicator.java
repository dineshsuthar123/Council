package com.council.research;

import com.council.config.CouncilProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports research-mode readiness without making Tavily a hard app dependency.
 */
@Component("research")
public class ResearchHealthIndicator implements HealthIndicator {

    private final CouncilProperties properties;

    public ResearchHealthIndicator(CouncilProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        CouncilProperties.ResearchConfig research = properties.getResearch();
        boolean enabled = research.isEnabled();
        boolean configured = research.getApiKey() != null && !research.getApiKey().isBlank();
        boolean available = enabled && configured;

        String reason;
        if (!enabled) {
            reason = "Research mode is disabled";
        } else if (!configured) {
            reason = "TAVILY_API_KEY is not configured";
        } else {
            reason = "Research provider is configured";
        }

        return Health.up()
                .withDetail("enabled", enabled)
                .withDetail("provider", research.getProvider())
                .withDetail("configured", configured)
                .withDetail("available", available)
                .withDetail("reason", reason)
                .build();
    }
}
