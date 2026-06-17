package com.council.trace;

import com.council.config.CouncilProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TraceRedactorTest {

    @Test
    @DisplayName("redacts common secrets, auth headers, and emails")
    void redactsSensitiveTraceArtifacts() {
        TraceRedactor redactor = new TraceRedactor(new CouncilProperties());

        String redacted = redactor.redact("""
                Authorization: Bearer sk-test_secret_1234567890
                {"apiKey":"tavily-live-secret-abcdef123456","email":"owner@example.com","password":"supersecret123"}
                """);

        assertTrue(redacted.contains("[REDACTED]"));
        assertTrue(redacted.contains("[REDACTED_EMAIL]"));
        assertFalse(redacted.contains("sk-test_secret_1234567890"));
        assertFalse(redacted.contains("tavily-live-secret-abcdef123456"));
        assertFalse(redacted.contains("owner@example.com"));
        assertFalse(redacted.contains("supersecret123"));
    }

    @Test
    @DisplayName("redaction can be disabled for controlled local diagnostics")
    void redactionCanBeDisabled() {
        CouncilProperties properties = new CouncilProperties();
        properties.getTrace().setRedactionEnabled(false);
        TraceRedactor redactor = new TraceRedactor(properties);

        assertEquals("apiKey=local-secret", redactor.redact("apiKey=local-secret"));
    }
}
