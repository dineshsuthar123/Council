package com.council.provider.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderDescriptorTest {

    @Test
    @DisplayName("isAvailableForRouting: true when enabled and not in cooldown")
    void availableWhenEnabledAndNotCooldown() {
        ProviderDescriptor desc = new ProviderDescriptor(
                "deepseek", "DeepSeek", "deepseek-chat", true,
                List.of(ProviderRole.DRAFT), 1, 0.85, 30, 2,
                List.of("openrouter"), false, 0.0);

        assertTrue(desc.isAvailableForRouting());
    }

    @Test
    @DisplayName("isAvailableForRouting: false when disabled")
    void unavailableWhenDisabled() {
        ProviderDescriptor desc = new ProviderDescriptor(
                "huggingface", "HF", "hf-model", false,
                List.of(ProviderRole.EXPERIMENTAL), 50, 0.60, 45, 1,
                List.of(), false, 0.0);

        assertFalse(desc.isAvailableForRouting());
    }

    @Test
    @DisplayName("isAvailableForRouting: false when in cooldown")
    void unavailableWhenInCooldown() {
        ProviderDescriptor desc = new ProviderDescriptor(
                "deepseek", "DeepSeek", "deepseek-chat", true,
                List.of(ProviderRole.DRAFT), 1, 0.85, 30, 2,
                List.of(), true, 0.5);

        assertFalse(desc.isAvailableForRouting());
    }

    @Test
    @DisplayName("hasRole returns true for assigned role")
    void hasRoleReturnsTrue() {
        ProviderDescriptor desc = new ProviderDescriptor(
                "gemini", "Gemini", "gemini-flash", true,
                List.of(ProviderRole.CRITIC, ProviderRole.PREMIUM_ESCALATION, ProviderRole.BASELINE),
                10, 0.92, 20, 1, List.of(), false, 0.0);

        assertTrue(desc.hasRole(ProviderRole.CRITIC));
        assertTrue(desc.hasRole(ProviderRole.PREMIUM_ESCALATION));
        assertTrue(desc.hasRole(ProviderRole.BASELINE));
        assertFalse(desc.hasRole(ProviderRole.DRAFT));
        assertFalse(desc.hasRole(ProviderRole.EXPERIMENTAL));
    }

    @Test
    @DisplayName("Record accessors return correct values")
    void accessorsCorrect() {
        ProviderDescriptor desc = new ProviderDescriptor(
                "groq", "Groq Llama", "llama-3", true,
                List.of(ProviderRole.DRAFT), 3, 0.80, 20, 2,
                List.of("together", "openrouter"), false, 0.05);

        assertEquals("groq", desc.name());
        assertEquals("Groq Llama", desc.displayName());
        assertEquals("llama-3", desc.model());
        assertTrue(desc.enabled());
        assertEquals(3, desc.priority());
        assertEquals(0.80, desc.reliability());
        assertEquals(20, desc.timeoutSeconds());
        assertEquals(2, desc.maxConcurrency());
        assertEquals(List.of("together", "openrouter"), desc.fallbackProviders());
        assertEquals(0.05, desc.recentFailureRate());
    }
}

