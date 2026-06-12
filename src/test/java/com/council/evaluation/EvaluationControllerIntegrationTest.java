package com.council.evaluation;

import com.council.api.dto.FinalResponse;
import com.council.orchestrator.ReasoningOrchestrator;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class EvaluationControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReasoningOrchestrator orchestrator;

    @Test
    @DisplayName("POST /api/v1/evaluate returns 202 with runId")
    void evaluateReturns202() throws Exception {
        when(orchestrator.reason(anyString())).thenReturn(new FinalResponse(
                "trace-1", "answer", "reason", List.of("gemini"), List.of(), 0.85));

        mockMvc.perform(post("/api/v1/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "integration-test",
                                    "tags": ["reasoning"],
                                    "runBaselines": false,
                                    "prompts": [
                                        {
                                            "prompt": "What is 2+2?",
                                            "expectedAnswer": "4",
                                            "expectedKeywords": ["four", "4"]
                                        }
                                    ]
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").exists())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.totalPrompts").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/evaluate with empty prompts returns 400")
    void evaluateEmptyPromptsReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "prompts": []
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/evaluate with blank prompt returns 400")
    void evaluateBlankPromptReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "prompts": [{"prompt": ""}]
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/evaluations/{invalid-id} returns 404")
    void getEvaluationNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/evaluations/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/evaluations returns paginated list")
    void listEvaluationsReturnsPaginatedResult() throws Exception {
        mockMvc.perform(get("/api/v1/evaluations")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.pageable").exists());
    }

    @Test
    @DisplayName("GET /api/v1/evaluations/{bad-format} returns 404")
    void getEvaluationInvalidUuid() throws Exception {
        mockMvc.perform(get("/api/v1/evaluations/not-a-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}

