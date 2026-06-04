package com.council.designagent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Fully-derived payment design snapshot — primary inputs plus every computed
 * value that the verifier inspects. All fields are immutable; repair produces
 * a new instance rather than mutating.
 * <p>
 * Derivation rules (single source of truth — mirrored by the verifier):
 * <ul>
 *   <li>{@code throughputPerPartition = tps / partitions}</li>
 *   <li>{@code latencyPerMessageMs    = 1000 / throughputPerPartition}</li>
 *   <li>{@code dlqTotalMsgsPerSec     = tps * failureRate}</li>
 *   <li>{@code dlqLoadPerPartition    = dlqTotalMsgsPerSec / dlqPartitions}</li>
 *   <li>{@code consumerCapacity       = consumerPods * perPodMsgsPerSec}</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "tps", "failure_rate", "per_pod_msgs_per_sec",
        "partitions", "dlq_partitions", "consumer_pods",
        "throughput_per_partition", "latency_per_message_ms",
        "dlq_total_msgs_per_sec", "dlq_load_per_partition",
        "consumer_capacity"
})
public record PaymentDesign(
        @JsonProperty("tps") int tps,
        @JsonProperty("failure_rate") double failureRate,
        @JsonProperty("per_pod_msgs_per_sec") double perPodMsgsPerSec,
        @JsonProperty("partitions") int partitions,
        @JsonProperty("dlq_partitions") int dlqPartitions,
        @JsonProperty("consumer_pods") int consumerPods,
        @JsonProperty("throughput_per_partition") double throughputPerPartition,
        @JsonProperty("latency_per_message_ms") double latencyPerMessageMs,
        @JsonProperty("dlq_total_msgs_per_sec") double dlqTotalMsgsPerSec,
        @JsonProperty("dlq_load_per_partition") double dlqLoadPerPartition,
        @JsonProperty("consumer_capacity") double consumerCapacity
) {

    /**
     * Build a {@link PaymentDesign} from primary inputs + proposed secondary values,
     * computing all derived fields deterministically.
     * Missing secondary values are seeded with safe minimal defaults
     * (partitions=1, dlqPartitions=1, consumerPods=1) so the verifier can run
     * and produce a failing constraint that the repairer then fixes.
     */
    public static PaymentDesign derive(PaymentDesignInput input) {
        int partitions = input.partitions() == null ? 1 : input.partitions();
        int dlqPartitions = input.dlqPartitions() == null ? 1 : input.dlqPartitions();
        int consumerPods = input.consumerPods() == null ? 1 : input.consumerPods();

        return deriveFrom(input.tps(), input.failureRate(), input.perPodMsgsPerSec(),
                partitions, dlqPartitions, consumerPods);
    }

    /**
     * Recompute all derived fields from primary inputs + secondary proposals.
     * Used by the repairer after any secondary value changes.
     */
    public static PaymentDesign deriveFrom(int tps,
                                           double failureRate,
                                           double perPodMsgsPerSec,
                                           int partitions,
                                           int dlqPartitions,
                                           int consumerPods) {
        double throughputPerPartition = (double) tps / partitions;
        double latencyPerMessageMs = throughputPerPartition == 0.0
                ? Double.POSITIVE_INFINITY
                : 1000.0 / throughputPerPartition;
        double dlqTotalMsgsPerSec = tps * failureRate;
        double dlqLoadPerPartition = dlqTotalMsgsPerSec / dlqPartitions;
        double consumerCapacity = consumerPods * perPodMsgsPerSec;

        return new PaymentDesign(
                tps,
                failureRate,
                perPodMsgsPerSec,
                partitions,
                dlqPartitions,
                consumerPods,
                throughputPerPartition,
                latencyPerMessageMs,
                dlqTotalMsgsPerSec,
                dlqLoadPerPartition,
                consumerCapacity
        );
    }

    /** Return a new design with updated partitions, recomputing derived fields. */
    public PaymentDesign withPartitions(int newPartitions) {
        return deriveFrom(tps, failureRate, perPodMsgsPerSec,
                newPartitions, dlqPartitions, consumerPods);
    }

    /** Return a new design with updated DLQ partitions, recomputing derived fields. */
    public PaymentDesign withDlqPartitions(int newDlqPartitions) {
        return deriveFrom(tps, failureRate, perPodMsgsPerSec,
                partitions, newDlqPartitions, consumerPods);
    }

    /** Return a new design with updated consumer pods, recomputing derived fields. */
    public PaymentDesign withConsumerPods(int newConsumerPods) {
        return deriveFrom(tps, failureRate, perPodMsgsPerSec,
                partitions, dlqPartitions, newConsumerPods);
    }
}
