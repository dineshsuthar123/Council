package com.council.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResearchNeedDetectorTest {

    private final ResearchNeedDetector detector = new ResearchNeedDetector();

    @Test
    @DisplayName("Detects current and explicit web research prompts")
    void detectsCurrentAndExplicitResearchPrompts() {
        assertTrue(detector.requiresResearch("What is the latest OpenAI model router pricing?"));
        assertTrue(detector.requiresResearch("Search the web and cite sources for Kubernetes 2026 changes"));
        assertTrue(detector.requiresResearch("Who is the current CEO of NVIDIA?"));
    }

    @Test
    @DisplayName("Does not require research for stable backend design prompts")
    void stableBackendPromptDoesNotRequireResearch() {
        assertFalse(detector.requiresResearch("Design a cache stampede control algorithm for Redis and PostgreSQL"));
    }
}
