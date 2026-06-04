package com.council.designagent;

import com.council.config.CouncilProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelfCorrectingDesignAgentTest {

    @Test
    @DisplayName("Valid design exits without repair iterations")
    void validDesignReturnsImmediately() {
        SelfCorrectingDesignAgent agent = agentWithMaxIterations(5);

        DesignAgentResult result = agent.correct(new PaymentDesignInput(
                50_000, 0.01, 5_000.0,
                50, 1, 10
        ));

        assertEquals("VALID", result.status());
        assertEquals(1.0, result.confidence());
        assertTrue(result.constraintsCheck().allPass());
        assertTrue(result.selfCheckIterations().isEmpty());
    }

    @Test
    @DisplayName("Repairs 50k TPS design with partition and consumer-capacity fixes")
    void repairsThroughputAndCapacity() {
        SelfCorrectingDesignAgent agent = agentWithMaxIterations(5);

        DesignAgentResult result = agent.correct(new PaymentDesignInput(
                50_000, 0.01, 5_000.0,
                1, 1, 1
        ));

        assertEquals("VALID", result.status());
        assertTrue(result.constraintsCheck().allPass());
        assertEquals(50, result.design().partitions());
        assertEquals(11, result.design().consumerPods());
        assertEquals(2, result.selfCheckIterations().size());
        assertEquals(PaymentDesignVerifier.THROUGHPUT_LIMIT,
                result.selfCheckIterations().get(0).failingConstraint());
        assertEquals(PaymentDesignVerifier.CONSUMER_CAPACITY,
                result.selfCheckIterations().get(1).failingConstraint());
    }

    @Test
    @DisplayName("Returns NO_VALID_DESIGN when iteration budget is exhausted")
    void returnsNoValidWhenIterationBudgetExhausted() {
        SelfCorrectingDesignAgent agent = agentWithMaxIterations(0);

        DesignAgentResult result = agent.correct(new PaymentDesignInput(
                50_000, 0.01, 5_000.0,
                1, 1, 1
        ));

        assertEquals("NO_VALID_DESIGN", result.status());
        assertEquals(0.0, result.confidence());
        assertFalse(result.constraintsCheck().allPass());
        assertTrue(result.selfCheckIterations().isEmpty());
    }

    private SelfCorrectingDesignAgent agentWithMaxIterations(int maxIterations) {
        CouncilProperties properties = new CouncilProperties();
        properties.getDesignAgent().setMaxIterations(maxIterations);
        PaymentDesignVerifier verifier = new PaymentDesignVerifier(properties);
        PaymentDesignRepairer repairer = new PaymentDesignRepairer(properties);
        return new SelfCorrectingDesignAgent(verifier, repairer, properties);
    }
}
