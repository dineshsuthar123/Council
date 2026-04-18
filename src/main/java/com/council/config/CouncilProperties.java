package com.council.config;

import com.council.provider.routing.ProviderRole;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Externalized configuration for the Council orchestration engine.
 */
@ConfigurationProperties(prefix = "council")
public class CouncilProperties {

    private Map<String, ProviderConfig> providers = new HashMap<>();
    private CriticConfig critic = new CriticConfig();
    private OrchestratorConfig orchestrator = new OrchestratorConfig();
    private RoutingConfig routing = new RoutingConfig();

    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }
    public CriticConfig getCritic() { return critic; }
    public void setCritic(CriticConfig critic) { this.critic = critic; }
    public OrchestratorConfig getOrchestrator() { return orchestrator; }
    public void setOrchestrator(OrchestratorConfig orchestrator) { this.orchestrator = orchestrator; }
    public RoutingConfig getRouting() { return routing; }
    public void setRouting(RoutingConfig routing) { this.routing = routing; }

    public static class ProviderConfig {
        private boolean enabled = true;
        private String apiKey = "";
        private String baseUrl = "";
        private String model = "";
        private int timeoutSeconds = 60;
        private double reliability = 0.8;
        private int maxTokens = 4096;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public double getReliability() { return reliability; }
        public void setReliability(double reliability) { this.reliability = reliability; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

        /** A provider is usable only when enabled AND an API key is present. */
        public boolean isUsable() {
            return enabled && apiKey != null && !apiKey.isBlank();
        }
    }

    public static class CriticConfig {
        private String provider = "claude";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
    }

    public static class OrchestratorConfig {
        private int maxRetries = 2;
        private int cooldownMinutes = 15;
        private int consecutive429Threshold = 3;
        private int draftTimeoutSeconds = 90;
        private int criticTimeoutSeconds = 120;

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public int getCooldownMinutes() { return cooldownMinutes; }
        public void setCooldownMinutes(int cooldownMinutes) { this.cooldownMinutes = cooldownMinutes; }
        public int getConsecutive429Threshold() { return consecutive429Threshold; }
        public void setConsecutive429Threshold(int consecutive429Threshold) { this.consecutive429Threshold = consecutive429Threshold; }
        public int getDraftTimeoutSeconds() { return draftTimeoutSeconds; }
        public void setDraftTimeoutSeconds(int draftTimeoutSeconds) { this.draftTimeoutSeconds = draftTimeoutSeconds; }
        public int getCriticTimeoutSeconds() { return criticTimeoutSeconds; }
        public void setCriticTimeoutSeconds(int criticTimeoutSeconds) { this.criticTimeoutSeconds = criticTimeoutSeconds; }
    }

    /**
     * Routing configuration — controls intelligent provider selection.
     * When {@code enabled = false}, the system falls back to legacy behaviour
     * (call all available providers).
     */
    public static class RoutingConfig {
        private boolean enabled = false;
        private int maxDraftProviders = 3;
        private int maxEscalationProviders = 1;
        private double escalationConfidenceThreshold = 0.45;
        private double escalationContradictionThreshold = 0.70;
        private Map<String, ProviderRouteConfig> providerRoutes = new HashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxDraftProviders() { return maxDraftProviders; }
        public void setMaxDraftProviders(int maxDraftProviders) { this.maxDraftProviders = maxDraftProviders; }
        public int getMaxEscalationProviders() { return maxEscalationProviders; }
        public void setMaxEscalationProviders(int maxEscalationProviders) { this.maxEscalationProviders = maxEscalationProviders; }
        public double getEscalationConfidenceThreshold() { return escalationConfidenceThreshold; }
        public void setEscalationConfidenceThreshold(double v) { this.escalationConfidenceThreshold = v; }
        public double getEscalationContradictionThreshold() { return escalationContradictionThreshold; }
        public void setEscalationContradictionThreshold(double v) { this.escalationContradictionThreshold = v; }
        public Map<String, ProviderRouteConfig> getProviderRoutes() { return providerRoutes; }
        public void setProviderRoutes(Map<String, ProviderRouteConfig> providerRoutes) { this.providerRoutes = providerRoutes; }
    }

    /**
     * Per-provider routing metadata (declared under {@code council.routing.provider-routes.<name>}).
     */
    public static class ProviderRouteConfig {
        private List<ProviderRole> roles = new ArrayList<>();
        private int priority = 100;
        private int maxConcurrency = 5;
        private List<String> fallbackProviders = new ArrayList<>();
        private String displayName = "";

        public List<ProviderRole> getRoles() { return roles; }
        public void setRoles(List<ProviderRole> roles) { this.roles = roles; }
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
        public int getMaxConcurrency() { return maxConcurrency; }
        public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
        public List<String> getFallbackProviders() { return fallbackProviders; }
        public void setFallbackProviders(List<String> fallbackProviders) { this.fallbackProviders = fallbackProviders; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
    }
}
