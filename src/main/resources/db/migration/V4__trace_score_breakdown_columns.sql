-- V4: Persist final answer score breakdowns on reasoning traces.
ALTER TABLE reasoning_traces ADD COLUMN answer_quality DOUBLE PRECISION;
ALTER TABLE reasoning_traces ADD COLUMN winner_confidence DOUBLE PRECISION;
ALTER TABLE reasoning_traces ADD COLUMN model_agreement DOUBLE PRECISION;
ALTER TABLE reasoning_traces ADD COLUMN score_dimensions TEXT;

UPDATE reasoning_traces
SET answer_quality = final_confidence
WHERE answer_quality IS NULL;
