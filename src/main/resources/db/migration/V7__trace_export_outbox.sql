-- V7: Internal sanitized trace export outbox for analytics/operations drains.
CREATE TABLE trace_export_outbox (
    id UUID PRIMARY KEY,
    trace_id UUID NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL,
    exported_at TIMESTAMP
);

CREATE INDEX idx_trace_export_outbox_created_at ON trace_export_outbox(created_at);
CREATE INDEX idx_trace_export_outbox_status ON trace_export_outbox(status);
