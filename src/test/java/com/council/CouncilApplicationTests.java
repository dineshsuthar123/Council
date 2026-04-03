package com.council;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CouncilApplicationTests {

    @Test
    void contextLoads() {
        // verifies the Spring context starts without errors
    }
}

