package com.council.designagent;

import com.council.config.CouncilProperties;
import org.springframework.stereotype.Component;

/**
 * Deterministically repairs the first failing payment-design constraint.
 */
@Component
public class PaymentDesignRepairer {

    private final CouncilProperties.DesignAgentConfig config;

    public PaymentDesignRepairer(CouncilProperties properties) {
        this.config = properties.getDesignAgent();
    }

    public RepairIteration repair(int iteration, PaymentDesign design, ConstraintReport report) {
        if (design == null) {
            throw new IllegalArgumentException("design must not be null");
        }
        if (report == null || report.firstFailingConstraint() == null) {
            return new RepairIteration(iteration, "NONE", "no repair needed", design);
        }

        ConstraintCheck failing = report.firstFailingConstraint();
        PaymentDesign repaired = switch (failing.name()) {
            case PaymentDesignVerifier.THROUGHPUT_LIMIT, PaymentDesignVerifier.LATENCY_REALITY ->
                    repairPartitions(design);
            case PaymentDesignVerifier.DLQ_CAPACITY -> repairDlqPartitions(design);
            case PaymentDesignVerifier.INTERNAL_CONSISTENCY -> recompute(design);
            case PaymentDesignVerifier.CONSUMER_CAPACITY -> repairConsumerPods(design);
            default -> design;
        };

        return new RepairIteration(iteration, failing.name(), describeRepair(failing.name(), design, repaired), repaired);
    }

    private PaymentDesign repairPartitions(PaymentDesign design) {
        double maxThroughput = Math.max(1.0, config.getPartitionsPerTpsDivisor());
        double maxThroughputAllowedByLatency = config.getMinLatencyMs() <= 0.0
                ? maxThroughput
                : 1000.0 / config.getMinLatencyMs();
        double effectiveMaxThroughput = Math.min(maxThroughput, maxThroughputAllowedByLatency);
        int requiredPartitions = ceilDiv(design.tps(), effectiveMaxThroughput);
        return design.withPartitions(Math.max(design.partitions(), requiredPartitions));
    }

    private PaymentDesign repairDlqPartitions(PaymentDesign design) {
        int requiredDlqPartitions = ceilDiv(design.dlqTotalMsgsPerSec(), config.getMaxDlqLoadPerPartition());
        return design.withDlqPartitions(Math.max(design.dlqPartitions(), requiredDlqPartitions));
    }

    private PaymentDesign repairConsumerPods(PaymentDesign design) {
        double requiredCapacity = design.tps() * Math.max(1.0, config.getCapacityHeadroomMultiplier());
        int requiredPods = ceilDiv(requiredCapacity, design.perPodMsgsPerSec());
        return design.withConsumerPods(Math.max(design.consumerPods(), requiredPods));
    }

    private PaymentDesign recompute(PaymentDesign design) {
        return PaymentDesign.deriveFrom(
                design.tps(),
                design.failureRate(),
                design.perPodMsgsPerSec(),
                design.partitions(),
                design.dlqPartitions(),
                design.consumerPods()
        );
    }

    private int ceilDiv(double numerator, double denominator) {
        if (denominator <= 0.0 || !Double.isFinite(denominator)) {
            return Integer.MAX_VALUE;
        }
        if (numerator <= 0.0) {
            return 1;
        }
        double quotient = numerator / denominator;
        double nearestInteger = Math.rint(quotient);
        if (Math.abs(quotient - nearestInteger) <= config.getConsistencyTolerance()) {
            return Math.max(1, (int) nearestInteger);
        }
        return Math.max(1, (int) Math.ceil(quotient));
    }

    private String describeRepair(String constraint, PaymentDesign before, PaymentDesign after) {
        return switch (constraint) {
            case PaymentDesignVerifier.THROUGHPUT_LIMIT, PaymentDesignVerifier.LATENCY_REALITY ->
                    "set partitions from " + before.partitions() + " to " + after.partitions();
            case PaymentDesignVerifier.DLQ_CAPACITY ->
                    "set dlq_partitions from " + before.dlqPartitions() + " to " + after.dlqPartitions();
            case PaymentDesignVerifier.INTERNAL_CONSISTENCY ->
                    "recomputed all derived fields from primary inputs";
            case PaymentDesignVerifier.CONSUMER_CAPACITY ->
                    "set consumer_pods from " + before.consumerPods() + " to " + after.consumerPods();
            default -> "no deterministic repair available";
        };
    }
}
