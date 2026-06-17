package com.council.trace;

import com.council.config.CouncilProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Redacts sensitive debug artifacts before trace persistence/export.
 */
@Component
public class TraceRedactor {

    private static final String REDACTED = "[REDACTED]";
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?[a-z0-9._~+/=-]{12,}");
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)(api[_-]?key|token|secret|password|authorization)(\\\"?\\s*[:=]\\s*\\\"?)[^\\\"\\s,}]{6,}");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern LONG_SECRET = Pattern.compile(
            "\\b(sk|pk|ghp|gho|hf|nvapi|or|tavily|xai|ds)-[a-zA-Z0-9_\\-]{12,}\\b");

    private final CouncilProperties properties;

    public TraceRedactor() {
        this(new CouncilProperties());
    }

    public TraceRedactor(CouncilProperties properties) {
        this.properties = properties;
    }

    public String redact(String value) {
        if (value == null || !properties.getTrace().isRedactionEnabled()) {
            return value;
        }
        String redacted = AUTH_HEADER.matcher(value).replaceAll("$1" + REDACTED);
        redacted = SECRET_FIELD.matcher(redacted).replaceAll("$1$2" + REDACTED);
        redacted = LONG_SECRET.matcher(redacted).replaceAll(REDACTED);
        redacted = EMAIL.matcher(redacted).replaceAll("[REDACTED_EMAIL]");
        return redacted;
    }

    public Map<String, String> redactMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> redacted = new LinkedHashMap<>();
        values.forEach((key, value) -> redacted.put(key, redact(value)));
        return redacted;
    }
}
