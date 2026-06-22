-- V9: persist provider-coverage/run-health diagnostics separately from answer-quality scoring.
ALTER TABLE reasoning_traces ADD COLUMN run_diagnostics TEXT;
