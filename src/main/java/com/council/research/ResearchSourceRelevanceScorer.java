package com.council.research;

import com.council.judge.TaskType;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic relevance filter for externally retrieved research. Prompt-provided sources bypass this
 * scorer because they are user-supplied evidence that must remain inspectable by the evaluator.
 */
public final class ResearchSourceRelevanceScorer {

    private static final Set<String> DECISION_TERMS = Set.of(
            "pricing", "price", "cost", "rate limit", "rate-limit", "latency", "reliability",
            "status", "incident", "outage", "degraded", "benchmark", "api");
    private static final Set<String> GENERIC_TREND_TERMS = Set.of(
            "ai trends", "future of ai", "ai weekly", "generic ai", "youtube", "trend video");
    private static final Set<String> PROVIDER_TERMS = Set.of(
            "openai", "anthropic", "claude", "gemini", "google", "groq", "nvidia", "deepseek",
            "blackbox", "openrouter", "mistral", "qwen", "provider a", "provider b");

    public Assessment assess(String userQuery, TaskType taskType, ResearchSource source) {
        if (source == null) {
            return new Assessment(0.0, "No source was returned by the research provider.", "Malformed external source.");
        }
        if (!isProviderDecisionPrompt(userQuery, taskType)) {
            return new Assessment(Math.max(0.45, source.score()),
                    "General research prompt; source retained without provider-migration filtering.", null);
        }

        String query = normalize(userQuery);
        String sourceText = normalize(String.join(" ", nullToEmpty(source.title()), nullToEmpty(source.domain()),
                nullToEmpty(source.url()), nullToEmpty(source.snippet())));

        if (containsAny(sourceText, GENERIC_TREND_TERMS)
                && !containsAny(sourceText, DECISION_TERMS)
                && !mentionsQueryProvider(query, sourceText)) {
            return new Assessment(0.10,
                    "Generic AI-trend content does not establish provider pricing, reliability, or routing facts.",
                    "Below the 0.45 provider-decision relevance threshold.");
        }

        double score = 0.15;
        boolean decisionTopic = containsAny(sourceText, DECISION_TERMS);
        boolean providerMatch = mentionsQueryProvider(query, sourceText);
        boolean official = source.sourceType() == SourceType.OFFICIAL_DOC
                || containsAny(sourceText, Set.of("docs.", "status.", "api."));
        boolean issue = source.sourceType() == SourceType.ISSUE || sourceText.contains("github.com");

        if (decisionTopic) {
            score += 0.34;
        }
        if (providerMatch) {
            score += 0.26;
        }
        if (official) {
            score += 0.25;
        }
        if (issue && containsAny(sourceText, Set.of("rate limit", "429", "incident", "outage", "latency"))) {
            score += 0.18;
        }
        if (!decisionTopic && !providerMatch) {
            score -= 0.12;
        }
        score = clamp(score);

        if (score < 0.45) {
            return new Assessment(score,
                    "External source lacks a provider-specific pricing, reliability, latency, incident, or API signal.",
                    "Below the 0.45 provider-decision relevance threshold.");
        }
        String reason = official
                ? "Official/provider API documentation is relevant to the requested decision."
                : issue ? "Provider-specific issue or incident signal is relevant to the requested decision."
                : "Source contains provider-decision evidence for the requested pricing, reliability, latency, or routing question.";
        return new Assessment(score, reason, null);
    }

    private boolean isProviderDecisionPrompt(String userQuery, TaskType taskType) {
        String query = normalize(userQuery);
        return taskType == TaskType.RESEARCH_REQUIRED
                && (containsAny(query, PROVIDER_TERMS)
                || containsAny(query, Set.of("provider migration", "provider selection", "api pricing",
                "rate limit", "reliability", "latency", "routing")));
    }

    private boolean mentionsQueryProvider(String query, String sourceText) {
        Set<String> matchingTerms = new LinkedHashSet<>();
        for (String term : PROVIDER_TERMS) {
            if (query.contains(term)) {
                matchingTerms.add(term);
            }
        }
        return matchingTerms.stream().anyMatch(sourceText::contains);
    }

    private static boolean containsAny(String text, Set<String> phrases) {
        return phrases.stream().anyMatch(text::contains);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record Assessment(double relevanceScore, String relevanceReason, String excludedReason) {
        public boolean isIncluded() {
            return relevanceScore >= 0.45 && excludedReason == null;
        }
    }
}
