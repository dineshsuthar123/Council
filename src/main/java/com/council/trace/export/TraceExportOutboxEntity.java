package com.council.trace.export;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trace_export_outbox")
public class TraceExportOutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trace_id", nullable = false)
    private UUID traceId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private TraceExportStatus status = TraceExportStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "exported_at")
    private Instant exportedAt;

    protected TraceExportOutboxEntity() {}

    public TraceExportOutboxEntity(UUID traceId, String payload) {
        this.traceId = traceId;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public UUID getTraceId() { return traceId; }
    public String getPayload() { return payload; }
    public TraceExportStatus getStatus() { return status; }
    public void setStatus(TraceExportStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExportedAt() { return exportedAt; }
    public void setExportedAt(Instant exportedAt) { this.exportedAt = exportedAt; }
}
