package com.council.trace.export;

import com.council.common.TraceStatus;
import com.council.config.CouncilProperties;
import com.council.trace.TraceEntity;
import com.council.trace.TraceRedactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TraceExportOutboxServiceTest {

    @Test
    @DisplayName("enqueue stores a bounded sanitized analytics payload")
    void enqueueStoresSanitizedPayload() {
        TraceExportOutboxRepository repository = mock(TraceExportOutboxRepository.class);
        CouncilProperties properties = new CouncilProperties();
        properties.getTrace().setExportPayloadMaxChars(600);
        TraceExportOutboxService service =
                new TraceExportOutboxService(repository, new TraceRedactor(properties), properties);
        TraceEntity entity = traceEntity();
        entity.setScoreDimensions("{\"apiKey\":\"sk-live-abcdef1234567890\",\"pseudocode\":0.9}");

        service.enqueue(entity);

        ArgumentCaptor<TraceExportOutboxEntity> captor = ArgumentCaptor.forClass(TraceExportOutboxEntity.class);
        verify(repository).save(captor.capture());
        TraceExportOutboxEntity outbox = captor.getValue();
        assertEquals(entity.getTraceId(), outbox.getTraceId());
        assertEquals(TraceExportStatus.PENDING, outbox.getStatus());
        assertTrue(outbox.getPayload().contains("\"answerQuality\":0.86"));
        assertTrue(outbox.getPayload().contains("[REDACTED]"));
        assertFalse(outbox.getPayload().contains("sk-live-abcdef1234567890"));
    }

    @Test
    @DisplayName("export outbox failures do not escape the user-facing request path")
    void enqueueFailureIsSwallowed() {
        TraceExportOutboxRepository repository = mock(TraceExportOutboxRepository.class);
        doThrow(new RuntimeException("database temporarily unavailable")).when(repository).save(any());
        CouncilProperties properties = new CouncilProperties();
        TraceExportOutboxService service =
                new TraceExportOutboxService(repository, new TraceRedactor(properties), properties);

        assertDoesNotThrow(() -> service.enqueue(traceEntity()));
    }

    @Test
    @DisplayName("export can be disabled without touching trace persistence")
    void disabledOutboxDoesNotSave() {
        TraceExportOutboxRepository repository = mock(TraceExportOutboxRepository.class);
        CouncilProperties properties = new CouncilProperties();
        properties.getTrace().setExportOutboxEnabled(false);
        TraceExportOutboxService service =
                new TraceExportOutboxService(repository, new TraceRedactor(properties), properties);

        service.enqueue(traceEntity());

        verify(repository, never()).save(any());
    }

    private TraceEntity traceEntity() {
        TraceEntity entity = new TraceEntity(UUID.randomUUID(), "query");
        entity.setStatus(TraceStatus.COMPLETED);
        entity.setAnswerQuality(0.86);
        entity.setWinnerConfidence(0.95);
        entity.setModelAgreement(null);
        entity.setTotalLatencyMs(1234L);
        entity.setUsedProviders("groq");
        entity.setFailedProviders("nvidia,openrouter");
        entity.setInvariantFindings("{\"overallCap\":0.85}");
        return entity;
    }
}
