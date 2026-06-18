package com.council.research;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Conservative detector for prompts where static model memory is likely unsafe.
 */
@Component
public class ResearchNeedDetector {

    public boolean requiresResearch(String query) {
        String text = normalize(query);
        if (text.isBlank()) {
            return false;
        }

        return containsAny(text,
                "latest", "most recent", "recently", "today", "yesterday", "tomorrow",
                "this week", "this month", "this year", "current", "now", "as of",
                "news", "price", "pricing", "stock", "crypto", "exchange rate",
                "weather", "score", "schedule", "standings", "release date",
                "new version", "changelog", "benchmark", "leaderboard",
                "president", "prime minister", "ceo", "cfo", "cto",
                "law", "regulation", "policy", "compliance", "standard",
                "search the web", "browse", "look up", "find sources", "cite sources",
                "with citations", "internet research", "online research",
                "source 1:", "source [1]:", "[s1]", "evidence pack", "citation correctness",
                "which sources should be trusted", "sources disagree", "source-ranking",
                "prompt-injection text found inside source", "official pricing page");
    }

    public String reason(String query) {
        String text = normalize(query);
        if (containsAny(text, "latest", "most recent", "current", "today", "now", "as of")) {
            return "Prompt asks for current or recent information.";
        }
        if (containsAny(text, "cite sources", "with citations", "find sources", "search the web",
                "browse", "look up", "source 1:", "[s1]", "evidence pack",
                "citation correctness", "which sources should be trusted", "sources disagree")) {
            return "Prompt explicitly requests external sources.";
        }
        if (containsAny(text, "price", "stock", "crypto", "weather", "score", "schedule")) {
            return "Prompt includes time-sensitive market, weather, or event data.";
        }
        if (containsAny(text, "law", "regulation", "policy", "standard", "ceo", "president")) {
            return "Prompt references facts that can change over time.";
        }
        return "Prompt appears to require external research.";
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
