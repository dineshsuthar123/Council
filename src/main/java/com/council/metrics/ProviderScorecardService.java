package com.council.metrics;

import com.council.api.dto.ProviderScorecardResponse;
import com.council.model.DraftResult;
import com.council.trace.TraceEntity;
import com.council.trace.TraceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Builds provider scorecards from persisted reasoning traces.
 */
@Service
public class ProviderScorecardService {

    private static final Logger log = LoggerFactory.getLogger(ProviderScorecardService.class);
    private static final int MAX_LIMIT = 1000;
    private static final int RECENT_ERROR_LIMIT = 3;

    private final TraceRepository traceRepository;
    private final ObjectMapper objectMapper;

    public ProviderScorecardService(TraceRepository traceRepository, ObjectMapper objectMapper) {
        this.traceRepository = traceRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ProviderScorecardResponse> scorecards(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Map<String, Accumulator> byProvider = new LinkedHashMap<>();

        traceRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, safeLimit))
                .forEach(trace -> collect(trace, byProvider));

        return byProvider.values().stream()
                .map(Accumulator::toResponse)
                .sorted(Comparator
                        .comparingDouble(ProviderScorecardResponse::successRate).reversed()
                        .thenComparingLong(ProviderScorecardResponse::p95LatencyMs)
                        .thenComparing(ProviderScorecardResponse::provider))
                .toList();
    }

    private void collect(TraceEntity trace, Map<String, Accumulator> byProvider) {
        String payload = trace.getDraftResults();
        if (payload == null || payload.isBlank()) {
            return;
        }

        try {
            List<DraftResult> drafts = objectMapper.readValue(payload, new TypeReference<>() {});
            for (DraftResult draft : drafts) {
                if (draft.provider() == null || draft.provider().isBlank()) {
                    continue;
                }
                byProvider.computeIfAbsent(draft.provider(), Accumulator::new)
                        .add(draft, trace.getCompletedAt() != null ? trace.getCompletedAt() : trace.getCreatedAt());
            }
        } catch (Exception e) {
            log.warn("[scorecard] Could not parse draft results for trace {}: {}",
                    trace.getTraceId(), e.getMessage());
        }
    }

    private static final class Accumulator {
        private final String provider;
        private String model;
        private int successes;
        private int failures;
        private double confidenceSum;
        private double bestConfidence;
        private Instant lastSeen;
        private final List<Long> latencies = new ArrayList<>();
        private final Deque<String> recentErrors = new ArrayDeque<>();

        private Accumulator(String provider) {
            this.provider = provider;
        }

        private void add(DraftResult draft, Instant seenAt) {
            if (model == null || model.isBlank()) {
                model = draft.model();
            }
            if (draft.isSuccess()) {
                successes++;
                confidenceSum += draft.confidence();
                bestConfidence = Math.max(bestConfidence, draft.confidence());
            } else {
                failures++;
                if (draft.errorMessage() != null && !draft.errorMessage().isBlank()) {
                    rememberError(draft.errorMessage());
                }
            }
            if (draft.latencyMs() >= 0L) {
                latencies.add(draft.latencyMs());
            }
            if (seenAt != null && (lastSeen == null || seenAt.isAfter(lastSeen))) {
                lastSeen = seenAt;
            }
        }

        private ProviderScorecardResponse toResponse() {
            int total = successes + failures;
            return new ProviderScorecardResponse(
                    provider,
                    model,
                    total,
                    successes,
                    failures,
                    total == 0 ? 0.0 : round2((double) successes / total),
                    percentileAverage(latencies),
                    percentile(latencies, 0.50),
                    percentile(latencies, 0.95),
                    successes == 0 ? 0.0 : round2(confidenceSum / successes),
                    round2(bestConfidence),
                    lastSeen == null ? null : lastSeen.toString(),
                    List.copyOf(recentErrors)
            );
        }

        private void rememberError(String error) {
            recentErrors.remove(error);
            recentErrors.addFirst(error);
            while (recentErrors.size() > RECENT_ERROR_LIMIT) {
                recentErrors.removeLast();
            }
        }
    }

    private static long percentileAverage(List<Long> values) {
        if (values.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (long value : values) {
            sum += value;
        }
        return Math.round((double) sum / values.size());
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
