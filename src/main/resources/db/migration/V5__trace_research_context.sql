-- V5: Persist shared research evidence packs on reasoning traces.
ALTER TABLE reasoning_traces ADD COLUMN research_context TEXT;
