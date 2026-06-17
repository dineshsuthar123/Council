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
    private SynthesizerConfig synthesizer = new SynthesizerConfig();
    private OrchestratorConfig orchestrator = new OrchestratorConfig();
    private RoutingConfig routing = new RoutingConfig();
    private DesignAgentConfig designAgent = new DesignAgentConfig();
    private ResearchConfig research = new ResearchConfig();
    private TraceConfig trace = new TraceConfig();

    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }
    public CriticConfig getCritic() { return critic; }
    public void setCritic(CriticConfig critic) { this.critic = critic; }
    public SynthesizerConfig getSynthesizer() { return synthesizer; }
    public void setSynthesizer(SynthesizerConfig synthesizer) { this.synthesizer = synthesizer; }
    public OrchestratorConfig getOrchestrator() { return orchestrator; }
    public void setOrchestrator(OrchestratorConfig orchestrator) { this.orchestrator = orchestrator; }
    public RoutingConfig getRouting() { return routing; }
    public void setRouting(RoutingConfig routing) { this.routing = routing; }
    public DesignAgentConfig getDesignAgent() { return designAgent; }
    public void setDesignAgent(DesignAgentConfig designAgent) { this.designAgent = designAgent; }
    public ResearchConfig getResearch() { return research; }
    public void setResearch(ResearchConfig research) { this.research = research; }
    public TraceConfig getTrace() { return trace; }
    public void setTrace(TraceConfig trace) { this.trace = trace; }

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

    public static class SynthesizerConfig {
        private String provider = "openrouter";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
    }

    public static class OrchestratorConfig {
        private int maxRetries = 2;
        private int cooldownMinutes = 15;
        private int consecutive429Threshold = 3;
        private int draftTimeoutSeconds = 90;
        private int criticTimeoutSeconds = 120;
        private int verifierTimeoutSeconds = 30;
        private int synthesisTimeoutSeconds = 60;
        private int requestTimeoutSeconds = 90;
        private int perProviderDeadlineSeconds = 30;
        private boolean earlyStopEnabled = false;
        private double earlyStopQualityThreshold = 0.88;
        private double earlyStopMinImprovement = 0.06;
        private Map<String, TaskBudgetConfig> taskBudgets = new HashMap<>();

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
        public int getVerifierTimeoutSeconds() { return verifierTimeoutSeconds; }
        public void setVerifierTimeoutSeconds(int verifierTimeoutSeconds) { this.verifierTimeoutSeconds = verifierTimeoutSeconds; }
        public int getSynthesisTimeoutSeconds() { return synthesisTimeoutSeconds; }
        public void setSynthesisTimeoutSeconds(int synthesisTimeoutSeconds) { this.synthesisTimeoutSeconds = synthesisTimeoutSeconds; }
        public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
        public int getPerProviderDeadlineSeconds() { return perProviderDeadlineSeconds; }
        public void setPerProviderDeadlineSeconds(int perProviderDeadlineSeconds) { this.perProviderDeadlineSeconds = perProviderDeadlineSeconds; }
        public boolean isEarlyStopEnabled() { return earlyStopEnabled; }
        public void setEarlyStopEnabled(boolean earlyStopEnabled) { this.earlyStopEnabled = earlyStopEnabled; }
        public double getEarlyStopQualityThreshold() { return earlyStopQualityThreshold; }
        public void setEarlyStopQualityThreshold(double earlyStopQualityThreshold) { this.earlyStopQualityThreshold = earlyStopQualityThreshold; }
        public double getEarlyStopMinImprovement() { return earlyStopMinImprovement; }
        public void setEarlyStopMinImprovement(double earlyStopMinImprovement) { this.earlyStopMinImprovement = earlyStopMinImprovement; }
        public Map<String, TaskBudgetConfig> getTaskBudgets() { return taskBudgets; }
        public void setTaskBudgets(Map<String, TaskBudgetConfig> taskBudgets) { this.taskBudgets = taskBudgets; }
    }

    public static class TaskBudgetConfig {
        private int requestTimeoutSeconds;
        private int draftTimeoutSeconds;
        private int perProviderDeadlineSeconds;
        private int maxDraftProviders;
        private double earlyStopQualityThreshold;

        public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
        public int getDraftTimeoutSeconds() { return draftTimeoutSeconds; }
        public void setDraftTimeoutSeconds(int draftTimeoutSeconds) { this.draftTimeoutSeconds = draftTimeoutSeconds; }
        public int getPerProviderDeadlineSeconds() { return perProviderDeadlineSeconds; }
        public void setPerProviderDeadlineSeconds(int perProviderDeadlineSeconds) { this.perProviderDeadlineSeconds = perProviderDeadlineSeconds; }
        public int getMaxDraftProviders() { return maxDraftProviders; }
        public void setMaxDraftProviders(int maxDraftProviders) { this.maxDraftProviders = maxDraftProviders; }
        public double getEarlyStopQualityThreshold() { return earlyStopQualityThreshold; }
        public void setEarlyStopQualityThreshold(double earlyStopQualityThreshold) { this.earlyStopQualityThreshold = earlyStopQualityThreshold; }
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

    public static class ResearchConfig {
        private boolean enabled = true;
        private String provider = "tavily";
        private String apiKey = "";
        private String baseUrl = "https://api.tavily.com/search";
        private int timeoutSeconds = 8;
        private int maxResults = 5;
        private int maxSnippetChars = 700;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        public int getMaxSnippetChars() { return maxSnippetChars; }
        public void setMaxSnippetChars(int maxSnippetChars) { this.maxSnippetChars = maxSnippetChars; }
    }

    public static class TraceConfig {
        private boolean redactionEnabled = true;
        private int retentionDays = 30;
        private int rawDebugRetentionDays = 7;
        private boolean exportOutboxEnabled = true;
        private int exportPayloadMaxChars = 12000;
        private int exportRetentionDays = 14;

        public boolean isRedactionEnabled() { return redactionEnabled; }
        public void setRedactionEnabled(boolean redactionEnabled) { this.redactionEnabled = redactionEnabled; }
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
        public int getRawDebugRetentionDays() { return rawDebugRetentionDays; }
        public void setRawDebugRetentionDays(int rawDebugRetentionDays) { this.rawDebugRetentionDays = rawDebugRetentionDays; }
        public boolean isExportOutboxEnabled() { return exportOutboxEnabled; }
        public void setExportOutboxEnabled(boolean exportOutboxEnabled) { this.exportOutboxEnabled = exportOutboxEnabled; }
        public int getExportPayloadMaxChars() { return exportPayloadMaxChars; }
        public void setExportPayloadMaxChars(int exportPayloadMaxChars) { this.exportPayloadMaxChars = exportPayloadMaxChars; }
        public int getExportRetentionDays() { return exportRetentionDays; }
        public void setExportRetentionDays(int exportRetentionDays) { this.exportRetentionDays = exportRetentionDays; }
    }

    /**
     * Self-correcting design agent configuration.
     * All thresholds are externalized so they can be tuned without code changes.
     */
    public static class DesignAgentConfig {
        private boolean enabled = true;
        private int maxIterations = 5;
        /** Minimum acceptable per-message latency (ms). */
        private double minLatencyMs = 0.5;
        /** Maximum acceptable sustained load per DLQ partition (msgs/sec). */
        private double maxDlqLoadPerPartition = 1000.0;
        /** Partitions-per-thousand-TPS rule numerator (1000 = 1 partition per 1000 TPS). */
        private int partitionsPerTpsDivisor = 1000;
        /** Tolerance for internal-consistency float comparisons. */
        private double consistencyTolerance = 1e-6;
        /** Headroom multiplier applied to repaired consumer pod counts (1.10 = +10 %). */
        private double capacityHeadroomMultiplier = 1.10;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxIterations() { return maxIterations; }
        public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
        public double getMinLatencyMs() { return minLatencyMs; }
        public void setMinLatencyMs(double minLatencyMs) { this.minLatencyMs = minLatencyMs; }
        public double getMaxDlqLoadPerPartition() { return maxDlqLoadPerPartition; }
        public void setMaxDlqLoadPerPartition(double v) { this.maxDlqLoadPerPartition = v; }
        public int getPartitionsPerTpsDivisor() { return partitionsPerTpsDivisor; }
        public void setPartitionsPerTpsDivisor(int partitionsPerTpsDivisor) {
            this.partitionsPerTpsDivisor = partitionsPerTpsDivisor;
        }
        public double getConsistencyTolerance() { return consistencyTolerance; }
        public void setConsistencyTolerance(double consistencyTolerance) {
            this.consistencyTolerance = consistencyTolerance;
        }
        public double getCapacityHeadroomMultiplier() { return capacityHeadroomMultiplier; }
        public void setCapacityHeadroomMultiplier(double capacityHeadroomMultiplier) {
            this.capacityHeadroomMultiplier = capacityHeadroomMultiplier;
        }
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
