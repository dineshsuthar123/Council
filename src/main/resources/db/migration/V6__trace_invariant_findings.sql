-- V6: Persist deterministic invariant critic findings on reasoning traces.
ALTER TABLE reasoning_traces ADD COLUMN invariant_findings TEXT;
