package com.council.designagent;

import com.council.config.CouncilProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure deterministic verifier for payment design constraints.
 * Returns all 5 constraint checks. Never throws.
 */
@Component
public class PaymentDesignVerifier {

    static final String THROUGHPUT_LIMIT = "THROUGHPUT_LIMIT";
    static final String DLQ_CAPACITY = "DLQ_CAPACITY";
    static final String LATENCY_REALITY = "LATENCY_REALITY";
    static final String INTERNAL_CONSISTENCY = "INTERNAL_CONSISTENCY";
    static final String CONSUMER_CAPACITY = "CONSUMER_CAPACITY";

    private final CouncilProperties.DesignAgentConfig config;

    public PaymentDesignVerifier(CouncilProperties properties) {
        this.config = properties.getDesignAgent();
    }

    public ConstraintReport verify(PaymentDesign design) {
        if (design == null) {
            throw new IllegalArgumentException("design must not be null");
        }

        List<ConstraintCheck> checks = new ArrayList<>();
        double maxThroughputPerPartition = Math.max(1.0, config.getPartitionsPerTpsDivisor());

        checks.add(new ConstraintCheck(
                THROUGHPUT_LIMIT,
                design.throughputPerPartition(),
                maxThroughputPerPartition,
                design.throughputPerPartition() <= maxThroughputPerPartition,
                "throughput_per_partition = tps / partitions <= max_msgs_per_sec_per_partition"
        ));

        checks.add(new ConstraintCheck(
                DLQ_CAPACITY,
                design.dlqLoadPerPartition(),
                config.getMaxDlqLoadPerPartition(),
                design.dlqLoadPerPartition() <= config.getMaxDlqLoadPerPartition(),
                "dlq_load_per_partition = (tps * failure_rate) / dlq_partitions <= max_dlq_load_per_partition"
        ));

        checks.add(new ConstraintCheck(
                LATENCY_REALITY,
                design.latencyPerMessageMs(),
                config.getMinLatencyMs(),
                design.latencyPerMessageMs() >= config.getMinLatencyMs(),
                "latency_per_message_ms = 1000 / throughput_per_partition >= min_latency_ms"
        ));

        double consistencyDelta = maxDerivedFieldDelta(design);
        checks.add(new ConstraintCheck(
                INTERNAL_CONSISTENCY,
                consistencyDelta,
                config.getConsistencyTolerance(),
                consistencyDelta <= config.getConsistencyTolerance(),
                "derived fields must match deterministic recomputation within tolerance"
        ));

        checks.add(new ConstraintCheck(
                CONSUMER_CAPACITY,
                design.consumerCapacity(),
                design.tps(),
                design.consumerCapacity() >= design.tps(),
                "consumer_capacity = consumer_pods * per_pod_msgs_per_sec >= tps"
        ));

        return ConstraintReport.from(checks);
    }

    private double maxDerivedFieldDelta(PaymentDesign design) {
        PaymentDesign expected = PaymentDesign.deriveFrom(
                design.tps(),
                design.failureRate(),
                design.perPodMsgsPerSec(),
                design.partitions(),
                design.dlqPartitions(),
                design.consumerPods()
        );

        double max = 0.0;
        max = Math.max(max, finiteDelta(design.throughputPerPartition(), expected.throughputPerPartition()));
        max = Math.max(max, finiteDelta(design.latencyPerMessageMs(), expected.latencyPerMessageMs()));
        max = Math.max(max, finiteDelta(design.dlqTotalMsgsPerSec(), expected.dlqTotalMsgsPerSec()));
        max = Math.max(max, finiteDelta(design.dlqLoadPerPartition(), expected.dlqLoadPerPartition()));
        max = Math.max(max, finiteDelta(design.consumerCapacity(), expected.consumerCapacity()));
        return max;
    }

    private double finiteDelta(double actual, double expected) {
        if (Double.compare(actual, expected) == 0) {
            return 0.0;
        }
        if (!Double.isFinite(actual) || !Double.isFinite(expected)) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.abs(actual - expected);
    }
}
