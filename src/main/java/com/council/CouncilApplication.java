package com.council;

import com.council.config.CouncilProperties;
import com.council.provider.blackbox.BlackboxProviderProperties;
import com.council.provider.ollama.OllamaProviderProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({CouncilProperties.class, BlackboxProviderProperties.class,
        OllamaProviderProperties.class})
public class CouncilApplication {

    public static void main(String[] args) {
        SpringApplication.run(CouncilApplication.class, args);
    }
}

