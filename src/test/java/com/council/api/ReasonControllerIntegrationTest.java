package com.council.api;

import com.council.api.dto.FinalResponse;
import com.council.orchestrator.ReasoningOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReasonControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReasoningOrchestrator orchestrator;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("POST /api/v1/reason returns 200 with valid response")
    void reasonReturns200() throws Exception {
        FinalResponse fake = new FinalResponse(
                "trace-id-123", "The answer is 42",
                "Winner by confidence", List.of("gemini"), List.of(), 0.85);

        when(orchestrator.reason(anyString())).thenReturn(fake);

        mockMvc.perform(post("/api/v1/reason")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"What is the meaning of life?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("trace-id-123"))
                .andExpect(jsonPath("$.finalAnswer").value("The answer is 42"))
                .andExpect(jsonPath("$.confidence").value(0.85));
    }

    @Test
    @DisplayName("POST /api/v1/reason with blank query returns 400")
    void blankQueryReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/reason")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /api/v1/reason with missing body returns 400")
    void missingBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/reason")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/design/self-correct returns deterministic repaired design")
    void designSelfCorrectReturnsRepairedDesign() throws Exception {
        mockMvc.perform(post("/api/v1/design/self-correct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tps": 50000,
                                  "failure_rate": 0.01,
                                  "per_pod_msgs_per_sec": 5000,
                                  "partitions": 1,
                                  "dlq_partitions": 1,
                                  "consumer_pods": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.design.partitions").value(50))
                .andExpect(jsonPath("$.design.consumer_pods").value(11));
    }

    @Test
    @DisplayName("GET /api/v1/health returns status")
    void healthReturnsStatus() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    @DisplayName("GET /api/v1/metrics returns provider info")
    void metricsReturnsProviderInfo() throws Exception {
        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers").exists());
    }

    @Test
    @DisplayName("GET /api/v1/traces/{bad-id} returns 404")
    void traceNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/traces/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/traces/{bad-id}/debug returns 404")
    void traceDebugNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/traces/00000000-0000-0000-0000-000000000000/debug"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/traces/{invalid-uuid} returns 404 gracefully")
    void traceInvalidUuidReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/traces/not-a-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/providers/status returns list")
    void providerStatusReturnsList() throws Exception {
        mockMvc.perform(get("/api/v1/providers/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].provider").exists());
    }

    @Test
    @DisplayName("GET /api/v1/traces returns paginated list")
    void traceListReturnsPaginatedResult() throws Exception {
        mockMvc.perform(get("/api/v1/traces")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.pageable").exists());
    }

    @Test
    @DisplayName("POST /api/v1/providers/{name}/reset-cooldown resets known provider")
    void resetCooldownSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/providers/claude/reset-cooldown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cooldownReset").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/providers/{name}/reset-cooldown returns 404 for unknown provider")
    void resetCooldownUnknownProvider() throws Exception {
        mockMvc.perform(post("/api/v1/providers/unknown-llm/reset-cooldown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}

