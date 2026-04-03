-- V1: Council reasoning traces schema
CREATE TABLE reasoning_traces (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id        UUID NOT NULL UNIQUE,
    user_query      TEXT NOT NULL,
    draft_results   JSONB,
    raw_responses   JSONB,
    critic_result   JSONB,
    judge_result    JSONB,
    final_answer    TEXT,
    final_confidence DOUBLE PRECISION,
    judge_reason    TEXT,
    used_providers  TEXT,
    failed_providers TEXT,
    total_latency_ms BIGINT,
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    error_message   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_traces_trace_id ON reasoning_traces(trace_id);
CREATE INDEX idx_traces_created_at ON reasoning_traces(created_at DESC);
CREATE INDEX idx_traces_status ON reasoning_traces(status);


