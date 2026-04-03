package com.council.trace;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TraceRepository extends JpaRepository<TraceEntity, UUID> {

    Optional<TraceEntity> findByTraceId(UUID traceId);

    Page<TraceEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

