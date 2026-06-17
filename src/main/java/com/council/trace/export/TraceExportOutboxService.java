package com.council.trace.export;

import com.council.config.CouncilProperties;
import com.council.trace.TraceEntity;
import com.council.trace.TraceRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Internal analytics/export outbox. It creates sanitized payloads that can be
 * drained by a future external sink without coupling export availability to
 * user-facing reasoning requests.
 */
@Service
public class TraceExportOutboxService {

    private static final Logger log = LoggerFactory.getLogger(TraceExportOutboxService.class);

    private final TraceExportOutboxRepository repository;
    private final TraceRedactor redactor;
    private final CouncilProperties properties;

    public TraceExportOutboxService(TraceExportOutboxRepository repository,
                                    TraceRedactor redactor,
                                    CouncilProperties properties) {
        this.repository = repository;
        this.redactor = redactor;
        this.properties = properties;
    }

    public void enqueue(TraceEntity entity) {
        if (!properties.getTrace().isExportOutboxEnabled() || entity == null) {
            return;
        }
        try {
            String payload = redactor.redact("""
                    {"traceId":"%s","status":"%s","answerQuality":%s,"winnerConfidence":%s,"modelAgreement":%s,"totalLatencyMs":%s,"usedProviders":"%s","failedProviders":"%s","dimensions":%s,"invariants":%s}
                    """.formatted(
                    entity.getTraceId(),
                    entity.getStatus(),
                    jsonNumber(entity.getAnswerQuality()),
                    jsonNumber(entity.getWinnerConfidence()),
                    jsonNumber(entity.getModelAgreement()),
                    jsonNumber(entity.getTotalLatencyMs()),
                    safe(entity.getUsedProviders()),
                    safe(entity.getFailedProviders()),
                    jsonObject(entity.getScoreDimensions()),
                    jsonObject(entity.getInvariantFindings())));
            payload = truncate(payload, properties.getTrace().getExportPayloadMaxChars());
            repository.save(new TraceExportOutboxEntity(entity.getTraceId(), payload));
        } catch (Exception e) {
            log.warn("[trace-export] Failed to enqueue trace export for {}: {}",
                    entity.getTraceId(), e.getMessage());
        }
    }

    public long purgeOlderThan(java.time.Instant cutoff) {
        return repository.deleteByCreatedAtBefore(cutoff);
    }

    private String jsonNumber(Number value) {
        return value == null ? "null" : value.toString();
    }

    private String jsonObject(String value) {
        return value == null || value.isBlank() ? "null" : value;
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String truncate(String value, int maxChars) {
        if (maxChars <= 0 || value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }
}
