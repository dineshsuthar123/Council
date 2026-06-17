package com.council.trace;

import com.council.api.dto.TraceDebugResponse;
import com.council.api.dto.TraceResponse;
import com.council.api.dto.TraceSummaryResponse;
import com.council.common.TraceStatus;
import com.council.trace.export.TraceExportOutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TraceServiceTest {

    private TraceRepository repository;
    private TraceMapper traceMapper;
    private TraceService service;

    @BeforeEach
    void setUp() {
        repository = mock(TraceRepository.class);
        traceMapper = new TraceMapper(new ObjectMapper());
        service = new TraceService(repository, traceMapper);
    }

    @Test
    @DisplayName("findByTraceId returns mapped response when entity exists")
    void findByTraceId_found() {
        UUID traceId = UUID.randomUUID();
        TraceEntity entity = new TraceEntity(traceId, "test query");
        entity.setFinalAnswer("42");
        entity.setFinalConfidence(0.9);
        entity.setUsedProviders("gemini");
        entity.setFailedProviders("");
        entity.setTotalLatencyMs(500L);

        when(repository.findByTraceId(traceId)).thenReturn(Optional.of(entity));

        Optional<TraceResponse> result = service.findByTraceId(traceId.toString());

        assertTrue(result.isPresent());
        assertEquals("42", result.get().finalAnswer());
        assertEquals("test query", result.get().userQuery());
    }

    @Test
    @DisplayName("findByTraceId returns empty when entity does not exist")
    void findByTraceId_notFound() {
        UUID traceId = UUID.randomUUID();
        when(repository.findByTraceId(traceId)).thenReturn(Optional.empty());

        Optional<TraceResponse> result = service.findByTraceId(traceId.toString());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("findByTraceId returns empty for invalid UUID")
    void findByTraceId_invalidUuid() {
        Optional<TraceResponse> result = service.findByTraceId("not-a-uuid");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("findDebugByTraceId returns mapped debug response when entity exists")
    void findDebugByTraceId_found() {
        UUID traceId = UUID.randomUUID();
        TraceEntity entity = new TraceEntity(traceId, "debug query");
        entity.setUsedProviders("gemini,claude");
        entity.setFailedProviders("deepseek");
        entity.setFinalAnswer("best answer");
        entity.setTotalLatencyMs(1200L);

        when(repository.findByTraceId(traceId)).thenReturn(Optional.of(entity));

        Optional<TraceDebugResponse> result = service.findDebugByTraceId(traceId.toString());

        assertTrue(result.isPresent());
        assertEquals(3, result.get().totalDrafts());
        assertEquals(2, result.get().successfulDrafts());
        assertEquals(1, result.get().failedDrafts());
    }

    @Test
    @DisplayName("findDebugByTraceId returns empty for invalid UUID")
    void findDebugByTraceId_invalidUuid() {
        Optional<TraceDebugResponse> result = service.findDebugByTraceId("bad-uuid");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("findAll returns paginated trace summaries")
    void findAll_returnsSummaries() {
        TraceEntity entity = new TraceEntity(UUID.randomUUID(), "page query");
        entity.setUsedProviders("gemini");
        entity.setFailedProviders("");
        entity.setFinalAnswer("answer");

        Page<TraceEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
        when(repository.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(page);

        Page<TraceSummaryResponse> result = service.findAll(0, 10);

        assertEquals(1, result.getTotalElements());
        assertEquals("page query", result.getContent().getFirst().userQuery());
    }

    @Test
    @DisplayName("findAll clamps page size to safe range")
    void findAll_clampsSize() {
        Page<TraceEntity> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 100), 0);
        when(repository.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(emptyPage);

        // size > 100 should be clamped to 100
        service.findAll(0, 500);
        verify(repository).findAllByOrderByCreatedAtDesc(PageRequest.of(0, 100));

        // negative page should be clamped to 0
        service.findAll(-1, 10);
        verify(repository).findAllByOrderByCreatedAtDesc(PageRequest.of(0, 10));
    }

    @Test
    @DisplayName("persistAsync saves entity via repository")
    void persistAsync_savesEntity() {
        service.persistAsync(
                UUID.randomUUID().toString(), "persist query",
                List.of(), null, null, null, 100L
        );
        verify(repository).save(any(TraceEntity.class));
    }

    @Test
    @DisplayName("persistAsync keeps saving traces when export outbox fails")
    void persistAsync_savesEntityWhenExportOutboxFails() {
        TraceExportOutboxService outboxService = mock(TraceExportOutboxService.class);
        doThrow(new RuntimeException("export unavailable")).when(outboxService).enqueue(any(TraceEntity.class));
        TraceService serviceWithOutbox = new TraceService(repository, traceMapper, outboxService);

        assertDoesNotThrow(() -> serviceWithOutbox.persistAsync(
                UUID.randomUUID().toString(), "persist query",
                List.of(), null, null, null, 100L
        ));

        verify(repository).save(any(TraceEntity.class));
        verify(outboxService).enqueue(any(TraceEntity.class));
    }
}

