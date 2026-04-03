-- V2: Evaluation / benchmark schema
CREATE TABLE evaluation_runs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id              UUID NOT NULL UNIQUE,
    name                VARCHAR(500),
    tags                VARCHAR(2000),
    provider_subset     VARCHAR(2000),
    run_baselines       BOOLEAN NOT NULL DEFAULT false,
    status              VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    total_prompts       INTEGER NOT NULL DEFAULT 0,
    completed_prompts   INTEGER NOT NULL DEFAULT 0,
    failed_prompts      INTEGER NOT NULL DEFAULT 0,
    aggregate_metrics   TEXT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMP WITH TIME ZONE
);

CREATE TABLE evaluation_prompt_results (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_run_id               UUID NOT NULL REFERENCES evaluation_runs(id) ON DELETE CASCADE,
    prompt_index                    INTEGER NOT NULL,
    prompt                          TEXT NOT NULL,
    expected_answer                 TEXT,
    expected_keywords               VARCHAR(5000),
    council_trace_id                VARCHAR(100),
    council_answer                  TEXT,
    council_confidence              DOUBLE PRECISION,
    council_latency_ms              BIGINT,
    council_winner_provider         VARCHAR(200),
    council_contradiction_severity  DOUBLE PRECISION,
    council_used_providers          VARCHAR(2000),
    council_failed_providers        VARCHAR(2000),
    council_judge_reason            TEXT,
    council_answer_length           INTEGER,
    baseline_results                TEXT,
    keyword_match_score             DOUBLE PRECISION,
    status                          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    error_message                   TEXT,
    created_at                      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_eval_runs_run_id ON evaluation_runs(run_id);
CREATE INDEX idx_eval_runs_status ON evaluation_runs(status);
CREATE INDEX idx_eval_runs_created_at ON evaluation_runs(created_at DESC);
CREATE INDEX idx_eval_results_run_id ON evaluation_prompt_results(evaluation_run_id);

