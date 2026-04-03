package com.council.trace;

import com.council.api.dto.FinalResponse;
import com.council.api.dto.TraceDebugResponse;
import com.council.api.dto.TraceResponse;
import com.council.api.dto.TraceSummaryResponse;
import com.council.model.CriticResult;
import com.council.model.DraftResult;
import com.council.model.JudgeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Async trace persistence service.
 * Mapping logic is delegated to {@link TraceMapper}.
 */
@Service
public class TraceService {

    private static final Logger log = LoggerFactory.getLogger(TraceService.class);

    private final TraceRepository repository;
    private final TraceMapper traceMapper;

    public TraceService(TraceRepository repository, TraceMapper traceMapper) {
        this.repository = repository;
        this.traceMapper = traceMapper;
    }

    /**
     * Persist a complete trace asynchronously. Failures are logged but never propagated
     * back to the caller – the user's response is unaffected.
     */
    @Async("traceExecutor")
    @Transactional
    public void persistAsync(String traceId,
                             String userQuery,
                             List<DraftResult> draftResults,
                             CriticResult criticResult,
                             JudgeResult judgeResult,
                             FinalResponse response,
                             long totalLatencyMs) {
        try {
            TraceEntity entity = new TraceEntity(UUID.fromString(traceId), userQuery);
            traceMapper.populateEntity(entity, draftResults, criticResult,
                    judgeResult, response, totalLatencyMs);
            entity.setCompletedAt(Instant.now());

            repository.save(entity);
            log.debug("[trace] Persisted trace {}", traceId);

        } catch (Exception e) {
            log.error("[trace] Failed to persist trace {}: {}", traceId, e.getMessage(), e);
        }
    }

    /**
     * List traces with pagination, most recent first.
     */
    @Transactional(readOnly = true)
    public Page<TraceSummaryResponse> findAll(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(safePage, safeSize))
                .map(traceMapper::toSummary);
    }

    /**
     * Retrieve a trace by its public traceId.
     */
    @Transactional(readOnly = true)
    public Optional<TraceResponse> findByTraceId(String traceId) {
        try {
            UUID uuid = UUID.fromString(traceId);
            return repository.findByTraceId(uuid).map(traceMapper::toResponse);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Retrieve a detailed debug view of a trace.
     */
    @Transactional(readOnly = true)
    public Optional<TraceDebugResponse> findDebugByTraceId(String traceId) {
        try {
            UUID uuid = UUID.fromString(traceId);
            return repository.findByTraceId(uuid).map(traceMapper::toDebugResponse);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
