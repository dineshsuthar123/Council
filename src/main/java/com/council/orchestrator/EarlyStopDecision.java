package com.council.orchestrator;

import com.council.judge.TaskType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Machine-readable explanation of whether draft fan-out may stop for this run. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EarlyStopDecision(
        boolean allowed,
        String reason,
        double threshold,
        int minValidDraftsRequired,
        int validDraftsSoFar,
        TaskType taskType,
        List<String> blockedReasons,
        String budgetReason
) {
    public EarlyStopDecision {
        reason = reason == null ? "" : reason;
        taskType = taskType == null ? TaskType.GENERAL_REASONING : taskType;
        blockedReasons = blockedReasons == null ? List.of() : List.copyOf(blockedReasons);
    }

    public static EarlyStopDecision continueWaiting(TaskType taskType, double threshold,
                                                    int minimum, int valid, List<String> blockedReasons) {
        return new EarlyStopDecision(false, "Continue collecting drafts", threshold, minimum, valid,
                taskType, blockedReasons, null);
    }
}
