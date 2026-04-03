package com.council.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Externalized configuration for the Council orchestration engine.
 */
@ConfigurationProperties(prefix = "council")
public class CouncilProperties {

    private Map<String, ProviderConfig> providers = new HashMap<>();
    private CriticConfig critic = new CriticConfig();
    private OrchestratorConfig orchestrator = new OrchestratorConfig();

    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }
    public CriticConfig getCritic() { return critic; }
    public void setCritic(CriticConfig critic) { this.critic = critic; }
    public OrchestratorConfig getOrchestrator() { return orchestrator; }
    public void setOrchestrator(OrchestratorConfig orchestrator) { this.orchestrator = orchestrator; }

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
}

