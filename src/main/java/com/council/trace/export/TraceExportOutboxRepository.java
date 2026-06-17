package com.council.trace.export;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface TraceExportOutboxRepository extends JpaRepository<TraceExportOutboxEntity, UUID> {

    long deleteByCreatedAtBefore(Instant cutoff);
}
