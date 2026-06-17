package com.council.api;

import com.council.api.dto.FinalResponse;
import com.council.events.PipelineEventBroadcaster;
import com.council.orchestrator.ReasoningOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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

    @MockBean
    private PipelineEventBroadcaster eventBroadcaster;

    @MockBean(name = "reasoningRunExecutor")
    private Executor reasoningRunExecutor;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("GET / serves static homepage publicly")
    void homepageIsPublic() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
    }

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
    @DisplayName("POST /api/v1/reason/runs accepts async run and exposes event URL")
    void asyncReasonRunReturns202() throws Exception {
        when(orchestrator.reason(anyString(), anyString())).thenReturn(new FinalResponse(
                UUID.randomUUID().toString(), "The answer is 42",
                "Winner by confidence", List.of("gemini"), List.of(), 0.85));

        mockMvc.perform(post("/api/v1/reason/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"What is the meaning of life?\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.eventsUrl").exists());

        verify(eventBroadcaster).publish(anyString(), eq("ACCEPTED"), eq("done"),
                anyString(), eq(0L), any());
        verify(reasoningRunExecutor).execute(any(Runnable.class));
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
    @WithMockUser(roles = "ADMIN")
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
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.research.enabled").value(true))
                .andExpect(jsonPath("$.research.available").value(false))
                .andExpect(jsonPath("$.research.reason").value("TAVILY_API_KEY is not configured"));
    }

    @Test
    @DisplayName("GET /api/v1/metrics returns provider info")
    @WithMockUser(roles = "ADMIN")
    void metricsReturnsProviderInfo() throws Exception {
        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers").exists());
    }

    @Test
    @DisplayName("GET /api/v1/traces/{bad-id} returns 404")
    @WithMockUser(roles = "ADMIN")
    void traceNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/traces/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/traces/{bad-id}/debug returns 404")
    @WithMockUser(roles = "ADMIN")
    void traceDebugNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/traces/00000000-0000-0000-0000-000000000000/debug"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/traces/{invalid-uuid} returns 404 gracefully")
    @WithMockUser(roles = "ADMIN")
    void traceInvalidUuidReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/traces/not-a-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/providers/status returns list")
    @WithMockUser(roles = "ADMIN")
    void providerStatusReturnsList() throws Exception {
        mockMvc.perform(get("/api/v1/providers/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].provider").exists());
    }

    @Test
    @DisplayName("GET /api/v1/providers/scorecards returns list")
    @WithMockUser(roles = "ADMIN")
    void providerScorecardsReturnsList() throws Exception {
        mockMvc.perform(get("/api/v1/providers/scorecards")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/traces returns paginated list")
    @WithMockUser(roles = "ADMIN")
    void traceListReturnsPaginatedResult() throws Exception {
        mockMvc.perform(get("/api/v1/traces")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.totalPages").exists())
                .andExpect(jsonPath("$.hasNext").exists());
    }

    @Test
    @DisplayName("POST /api/v1/providers/{name}/reset-cooldown resets known provider")
    @WithMockUser(roles = "ADMIN")
    void resetCooldownSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/providers/claude/reset-cooldown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cooldownReset").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/providers/{name}/reset-cooldown returns 404 for unknown provider")
    @WithMockUser(roles = "ADMIN")
    void resetCooldownUnknownProvider() throws Exception {
        mockMvc.perform(post("/api/v1/providers/unknown-llm/reset-cooldown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET unknown /api/v1/* returns structured 404 JSON")
    void unknownApiPathReturnsStructured404() throws Exception {
        mockMvc.perform(get("/api/v1/providers"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No endpoint found for GET /api/v1/providers"));
    }

    @Test
    @DisplayName("GET /api/v1/traces requires admin authentication")
    void traceListRequiresAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/traces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/v1/traces/{traceId}/debug requires admin authentication")
    void traceDebugRequiresAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/traces/00000000-0000-0000-0000-000000000000/debug"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/v1/providers/status requires admin authentication")
    void providerStatusRequiresAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/providers/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("POST /api/v1/providers/{name}/reset-cooldown requires admin authentication")
    void resetCooldownRequiresAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/providers/claude/reset-cooldown"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/v1/metrics requires admin authentication")
    void metricsRequiresAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /actuator/health requires admin authentication")
    void actuatorHealthRequiresAdmin() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Non-admin user receives 403 for admin endpoints")
    @WithMockUser(roles = "USER")
    void adminEndpointRejectsNonAdminUser() throws Exception {
        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("Admin user can access actuator health")
    @WithMockUser(roles = "ADMIN")
    void adminCanAccessActuatorHealth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components.research.details.available").value(false))
                .andExpect(jsonPath("$.components.research.details.reason").value("TAVILY_API_KEY is not configured"));
    }
}

