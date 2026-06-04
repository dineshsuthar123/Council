package com.council.designagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Raw primary inputs supplied to the self-correcting design agent.
 * All derived values (partitions, DLQ partitions, consumer pods, …) start
 * as the caller's *proposal* and may be repaired by the agent.
 * <p>
 * Any {@code null} numeric field is treated as "agent must derive this" and
 * will be freshly computed from the primary inputs ({@code tps},
 * {@code failureRate}, {@code perPodMsgsPerSec}).
 *
 * @param tps               target transactions per second (primary input)
 * @param failureRate       expected downstream failure rate, e.g. 0.001 (primary input)
 * @param perPodMsgsPerSec  measured single-consumer-pod throughput (primary input)
 * @param partitions        proposed main-topic partition count (may be null → derive)
 * @param dlqPartitions     proposed DLQ partition count (may be null → derive)
 * @param consumerPods      proposed consumer pod count (may be null → derive)
 */
public record PaymentDesignInput(
        @JsonProperty("tps") int tps,
        @JsonProperty("failure_rate") double failureRate,
        @JsonProperty("per_pod_msgs_per_sec") double perPodMsgsPerSec,
        @JsonProperty("partitions") Integer partitions,
        @JsonProperty("dlq_partitions") Integer dlqPartitions,
        @JsonProperty("consumer_pods") Integer consumerPods
) {

    @JsonCreator
    public PaymentDesignInput {
        if (tps <= 0) {
            throw new IllegalArgumentException("tps must be > 0, got " + tps);
        }
        if (failureRate < 0.0 || failureRate > 1.0) {
            throw new IllegalArgumentException("failureRate must be in [0,1], got " + failureRate);
        }
        if (perPodMsgsPerSec <= 0.0) {
            throw new IllegalArgumentException("perPodMsgsPerSec must be > 0, got " + perPodMsgsPerSec);
        }
        if (partitions != null && partitions <= 0) {
            throw new IllegalArgumentException("partitions must be > 0 when provided, got " + partitions);
        }
        if (dlqPartitions != null && dlqPartitions <= 0) {
            throw new IllegalArgumentException("dlqPartitions must be > 0 when provided, got " + dlqPartitions);
        }
        if (consumerPods != null && consumerPods <= 0) {
            throw new IllegalArgumentException("consumerPods must be > 0 when provided, got " + consumerPods);
        }
    }
}
