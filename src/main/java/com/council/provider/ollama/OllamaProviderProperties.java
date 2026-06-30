package com.council.provider.ollama;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for local Ollama logical providers.
 */
@ConfigurationProperties(prefix = "council.providers.ollama")
public class OllamaProviderProperties {

    private boolean enabled = true;
    private String baseUrl = "http://localhost:11434";
    private Defaults defaults = new Defaults();
    private Preflight preflight = new Preflight();
    private Map<String, ModelConfig> models = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public Defaults getDefaults() { return defaults; }
    public void setDefaults(Defaults defaults) { this.defaults = defaults == null ? new Defaults() : defaults; }
    public Preflight getPreflight() { return preflight; }
    public void setPreflight(Preflight preflight) { this.preflight = preflight == null ? new Preflight() : preflight; }
    public Map<String, ModelConfig> getModels() { return models; }
    public void setModels(Map<String, ModelConfig> models) {
        this.models = models == null ? new LinkedHashMap<>() : new LinkedHashMap<>(models);
    }

    public static class Defaults {
        private int timeoutMs = 60_000;
        private double temperature = 0.2;
        private int numPredict = 2048;
        private int priority = 20;
        private double reliability = 0.72;

        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getNumPredict() { return numPredict; }
        public void setNumPredict(int numPredict) { this.numPredict = numPredict; }
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
        public double getReliability() { return reliability; }
        public void setReliability(double reliability) { this.reliability = reliability; }
    }

    public static class Preflight {
        private boolean enabled = false;
        private int timeoutMs = 10_000;
        private int numPredict = 8;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getNumPredict() { return numPredict; }
        public void setNumPredict(int numPredict) { this.numPredict = numPredict; }
    }

    public static class ModelConfig {
        private boolean enabled = true;
        private String providerId = "";
        private String displayName = "";
        private String model = "";
        private String role = "general";
        private Integer timeoutMs;
        private Double temperature;
        private Integer numPredict;
        private Integer priority;
        private Double reliability;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProviderId() { return providerId; }
        public void setProviderId(String providerId) { this.providerId = providerId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public Integer getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Integer getNumPredict() { return numPredict; }
        public void setNumPredict(Integer numPredict) { this.numPredict = numPredict; }
        public Integer getPriority() { return priority; }
        public void setPriority(Integer priority) { this.priority = priority; }
        public Double getReliability() { return reliability; }
        public void setReliability(Double reliability) { this.reliability = reliability; }
    }
}
