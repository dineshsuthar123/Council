package com.council.provider.ollama;

import com.council.config.CouncilProperties;
import com.council.config.ProviderMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

class OllamaProviderPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsFourLogicalProvidersAndLocalOnlyMode() {
        contextRunner.withPropertyValues(
                "council.provider-mode=local_only",
                "council.providers.ollama.enabled=true",
                "council.providers.ollama.base-url=http://host.docker.internal:11434",
                "council.providers.ollama.defaults.timeout-ms=65000",
                "council.providers.ollama.defaults.temperature=0.15",
                "council.providers.ollama.defaults.num-predict=1024",
                "council.providers.ollama.models.qwen-coder.enabled=true",
                "council.providers.ollama.models.qwen-coder.provider-id=ollama-qwen-coder",
                "council.providers.ollama.models.qwen-coder.display-name=Ollama Qwen Coder 7B",
                "council.providers.ollama.models.qwen-coder.model=qwen2.5-coder:7b",
                "council.providers.ollama.models.qwen-coder.role=coding",
                "council.providers.ollama.models.qwen-coder.timeout-ms=60000",
                "council.providers.ollama.models.deepseek.enabled=true",
                "council.providers.ollama.models.deepseek.provider-id=ollama-deepseek",
                "council.providers.ollama.models.deepseek.model=deepseek-r1:8b",
                "council.providers.ollama.models.deepseek.role=reasoning",
                "council.providers.ollama.models.deepseek.timeout-ms=90000",
                "council.providers.ollama.models.llama.enabled=true",
                "council.providers.ollama.models.llama.provider-id=ollama-llama",
                "council.providers.ollama.models.llama.model=llama3.1:8b",
                "council.providers.ollama.models.llama.role=general",
                "council.providers.ollama.models.gemma.enabled=true",
                "council.providers.ollama.models.gemma.provider-id=ollama-gemma",
                "council.providers.ollama.models.gemma.model=gemma3:4b",
                "council.providers.ollama.models.gemma.role=summarizer",
                "council.providers.ollama.models.gemma.timeout-ms=45000"
        ).run(context -> {
            CouncilProperties council = context.getBean(CouncilProperties.class);
            OllamaProviderProperties ollama = context.getBean(OllamaProviderProperties.class);

            assertEquals(ProviderMode.LOCAL_ONLY, council.getProviderMode());
            assertEquals("http://host.docker.internal:11434", ollama.getBaseUrl());
            assertEquals(65_000, ollama.getDefaults().getTimeoutMs());
            assertEquals(0.15, ollama.getDefaults().getTemperature());
            assertEquals(1024, ollama.getDefaults().getNumPredict());
            assertEquals(4, ollama.getModels().size());
            assertEquals("qwen2.5-coder:7b", ollama.getModels().get("qwen-coder").getModel());
            assertEquals(60_000, ollama.getModels().get("qwen-coder").getTimeoutMs());
            assertEquals(90_000, ollama.getModels().get("deepseek").getTimeoutMs());
            assertEquals(45_000, ollama.getModels().get("gemma").getTimeoutMs());
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CouncilProperties.class, OllamaProviderProperties.class})
    static class PropertiesConfiguration {
    }
}
