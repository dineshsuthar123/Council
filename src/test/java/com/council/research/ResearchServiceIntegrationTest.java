package com.council.research;

import com.council.config.CouncilProperties;
import com.council.judge.TaskType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = {
                ResearchService.class,
                ResearchNeedDetector.class,
                ResearchQueryPlanner.class,
                PromptProvidedEvidenceParser.class
        },
        properties = {
                "council.research.enabled=true",
                "council.research.provider=tavily",
                "council.research.api-key=test-tavily-key",
                "council.research.max-results=3"
        })
@EnableConfigurationProperties(CouncilProperties.class)
class ResearchServiceIntegrationTest {

    @Autowired
    private ResearchService researchService;

    @MockBean
    private ResearchClient researchClient;

    @Test
    @DisplayName("Research-required prompt builds evidence pack through mocked Tavily client")
    void researchRequiredPromptBuildsEvidencePackWithMockedClient() {
        ResearchSource source = new ResearchSource(
                "S1",
                "Current routing benchmark",
                "https://example.com/routing-benchmark",
                "example.com",
                "Current benchmark notes for model routing.",
                "2026-06-01",
                0.91);
        when(researchClient.search(anyList(), anyInt())).thenReturn(List.of(source));

        ResearchPack pack = researchService.buildEvidencePack(
                "What is the latest low-latency multi-model routing benchmark today?",
                TaskType.BACKEND_ARCHITECTURE);

        assertTrue(pack.required());
        assertTrue(pack.attempted());
        assertTrue(pack.hasSources());
        assertEquals("Prompt asks for current or recent information.", pack.reason());
        assertEquals("S1", pack.sources().getFirst().id());
        assertNull(pack.errorMessage());

        ArgumentCaptor<List<String>> queriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(researchClient).search(queriesCaptor.capture(), anyInt());
        assertFalse(queriesCaptor.getValue().isEmpty());
        assertTrue(queriesCaptor.getValue().getFirst().contains("latest"));
    }
}
