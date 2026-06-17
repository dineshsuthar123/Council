package com.council.trace;

import com.council.config.CouncilProperties;
import com.council.trace.export.TraceExportOutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TraceRetentionServiceTest {

    @Test
    @DisplayName("raw debug retention strips sensitive artifacts without deleting summaries")
    void stripsOldRawDebugArtifacts() {
        TraceRepository repository = mock(TraceRepository.class);
        TraceExportOutboxService outboxService = mock(TraceExportOutboxService.class);
        CouncilProperties properties = new CouncilProperties();
        properties.getTrace().setRawDebugRetentionDays(7);
        TraceRetentionService service = new TraceRetentionService(repository, outboxService, properties);
        TraceEntity entity = new TraceEntity(UUID.randomUUID(), "debug-heavy query");
        entity.setRawResponses("{\"groq\":\"raw\"}");
        entity.setDraftResults("{\"drafts\":[]}");
        entity.setCriticResult("{\"summary\":\"ok\"}");
        entity.setJudgeResult("{\"winner\":\"groq\"}");
        entity.setFinalAnswer("safe summary remains");
        when(repository.findTop100ByCreatedAtBeforeAndRawResponsesIsNotNull(any(Instant.class)))
                .thenReturn(List.of(entity));

        service.stripOldRawDebugArtifacts();

        assertNull(entity.getRawResponses());
        assertNull(entity.getDraftResults());
        assertNull(entity.getCriticResult());
        assertNull(entity.getJudgeResult());
        assertEquals("safe summary remains", entity.getFinalAnswer());
        verify(repository).saveAll(List.of(entity));
    }

    @Test
    @DisplayName("trace and export retention use independently configured windows")
    void appliesTraceAndExportRetention() {
        TraceRepository repository = mock(TraceRepository.class);
        TraceExportOutboxService outboxService = mock(TraceExportOutboxService.class);
        CouncilProperties properties = new CouncilProperties();
        properties.getTrace().setRetentionDays(30);
        properties.getTrace().setExportRetentionDays(14);
        TraceRetentionService service = new TraceRetentionService(repository, outboxService, properties);

        service.deleteExpiredTraces();
        service.purgeExportOutbox();

        verify(repository).deleteByCreatedAtBefore(any(Instant.class));
        verify(outboxService).purgeOlderThan(any(Instant.class));
    }
}
