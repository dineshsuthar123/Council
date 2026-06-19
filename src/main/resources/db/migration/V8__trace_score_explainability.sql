-- V8: Persist the actual score inputs and caps used for trace explainability.
ALTER TABLE reasoning_traces ADD COLUMN score_breakdown TEXT;
