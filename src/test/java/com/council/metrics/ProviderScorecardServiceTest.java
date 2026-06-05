package com.council.metrics;

import com.council.model.DraftResult;
import com.council.trace.TraceEntity;
import com.council.trace.TraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderScorecardServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TraceRepository traceRepository = mock(TraceRepository.class);
    private final ProviderScorecardService service =
            new ProviderScorecardService(traceRepository, objectMapper);

    @Test
    @DisplayName("Scorecards aggregate success, latency percentiles, and confidence")
    void scorecardsAggregateProviderMetrics() throws Exception {
        TraceEntity trace = new TraceEntity(UUID.randomUUID(), "query");
        trace.setDraftResults(objectMapper.writeValueAsString(List.of(
                DraftResult.success("gemini", "gemini-2.5-flash",
                        "a", "s", List.of(), List.of(), 0.90, 100, "raw"),
                DraftResult.success("gemini", "gemini-2.5-flash",
                        "b", "s", List.of(), List.of(), 0.70, 300, "raw"),
                DraftResult.failure("gemini", "gemini-2.5-flash",
                        "rate limited", 900),
                DraftResult.success("groq", "llama-3.3",
                        "c", "s", List.of(), List.of(), 0.82, 80, "raw")
        )));

        when(traceRepository.findAllByOrderByCreatedAtDesc(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(trace)));

        var scorecards = service.scorecards(200);

        var groq = scorecards.stream()
                .filter(item -> item.provider().equals("groq"))
                .findFirst()
                .orElseThrow();
        var gemini = scorecards.stream()
                .filter(item -> item.provider().equals("gemini"))
                .findFirst()
                .orElseThrow();

        assertEquals(1, groq.totalCalls());
        assertEquals(1.0, groq.successRate());
        assertEquals(80, groq.p95LatencyMs());

        assertEquals(3, gemini.totalCalls());
        assertEquals(2, gemini.successes());
        assertEquals(1, gemini.failures());
        assertEquals(0.67, gemini.successRate());
        assertEquals(300, gemini.p50LatencyMs());
        assertEquals(900, gemini.p95LatencyMs());
        assertEquals(0.80, gemini.avgConfidence());
        assertTrue(gemini.recentErrors().contains("rate limited"));
    }

    @Test
    @DisplayName("Malformed trace payloads are ignored instead of failing scorecards")
    void malformedTracePayloadsAreIgnored() {
        TraceEntity trace = new TraceEntity(UUID.randomUUID(), "query");
        trace.setDraftResults("{not-json");
        when(traceRepository.findAllByOrderByCreatedAtDesc(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(trace)));

        assertTrue(service.scorecards(200).isEmpty());
    }
}
