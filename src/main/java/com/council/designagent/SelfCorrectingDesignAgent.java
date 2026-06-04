package com.council.designagent;

import com.council.config.CouncilProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic generate-verify-repair loop for payment-system capacity designs.
 */
@Service
public class SelfCorrectingDesignAgent {

    private final PaymentDesignVerifier verifier;
    private final PaymentDesignRepairer repairer;
    private final CouncilProperties.DesignAgentConfig config;

    public SelfCorrectingDesignAgent(PaymentDesignVerifier verifier,
                                     PaymentDesignRepairer repairer,
                                     CouncilProperties properties) {
        this.verifier = verifier;
        this.repairer = repairer;
        this.config = properties.getDesignAgent();
    }

    public DesignAgentResult correct(PaymentDesignInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }

        PaymentDesign design = PaymentDesign.derive(input);
        ConstraintReport report = verifier.verify(design);
        List<RepairIteration> iterations = new ArrayList<>();

        if (!config.isEnabled()) {
            return DesignAgentResult.noValidDesign(design, report, iterations);
        }
        if (report.allPass()) {
            return DesignAgentResult.valid(design, report, iterations);
        }

        int maxIterations = Math.max(0, config.getMaxIterations());
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            RepairIteration repair = repairer.repair(iteration, design, report);
            iterations.add(repair);

            if (repair.stateAfter().equals(design)) {
                break;
            }

            design = repair.stateAfter();
            report = verifier.verify(design);
            if (report.allPass()) {
                return DesignAgentResult.valid(design, report, iterations);
            }
        }

        return DesignAgentResult.noValidDesign(design, report, iterations);
    }
}
