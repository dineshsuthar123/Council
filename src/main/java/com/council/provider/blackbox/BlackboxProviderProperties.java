package com.council.provider.blackbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for Blackbox AI logical providers. Each enabled model becomes an independent Council adapter.
 */
@ConfigurationProperties(prefix = "council.providers.blackbox")
public class BlackboxProviderProperties {

    private boolean enabled = true;
    private String baseUrl = "https://api.blackbox.ai/chat/completions";
    private Defaults defaults = new Defaults();
    private Map<String, ModelConfig> models = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public Defaults getDefaults() { return defaults; }
    public void setDefaults(Defaults defaults) { this.defaults = defaults == null ? new Defaults() : defaults; }
    public Map<String, ModelConfig> getModels() { return models; }
    public void setModels(Map<String, ModelConfig> models) {
        this.models = models == null ? new LinkedHashMap<>() : new LinkedHashMap<>(models);
    }

    public static class Defaults {
        private int timeoutMs = 60_000;
        private double temperature = 0.2;
        private int maxTokens = 4096;
        private int priority = 100;
        private double reliability = 0.8;

        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
        public double getReliability() { return reliability; }
        public void setReliability(double reliability) { this.reliability = reliability; }
    }

    public static class ModelConfig {
        private boolean enabled;
        private String providerId = "";
        private String displayName = "";
        private String apiKey = "";
        private String model = "";
        private Integer timeoutMs;
        private Double temperature;
        private Integer maxTokens;
        private Integer priority;
        private Double reliability;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProviderId() { return providerId; }
        public void setProviderId(String providerId) { this.providerId = providerId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public Integer getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
        public Integer getPriority() { return priority; }
        public void setPriority(Integer priority) { this.priority = priority; }
        public Double getReliability() { return reliability; }
        public void setReliability(Double reliability) { this.reliability = reliability; }
    }
}
