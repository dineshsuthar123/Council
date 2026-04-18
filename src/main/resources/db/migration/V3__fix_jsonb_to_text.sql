-- V3: Fix JSONB columns to TEXT for Hibernate compatibility
-- JSONB was used in V1 but the JPA entity maps these as plain strings.
ALTER TABLE reasoning_traces ALTER COLUMN draft_results TYPE TEXT;
ALTER TABLE reasoning_traces ALTER COLUMN raw_responses TYPE TEXT;
ALTER TABLE reasoning_traces ALTER COLUMN critic_result TYPE TEXT;
ALTER TABLE reasoning_traces ALTER COLUMN judge_result TYPE TEXT;

