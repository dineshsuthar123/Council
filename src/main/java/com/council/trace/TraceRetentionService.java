package com.council.trace;

import com.council.config.CouncilProperties;
import com.council.trace.export.TraceExportOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class TraceRetentionService {

    private static final Logger log = LoggerFactory.getLogger(TraceRetentionService.class);

    private final TraceRepository repository;
    private final TraceExportOutboxService exportOutboxService;
    private final CouncilProperties properties;

    public TraceRetentionService(TraceRepository repository,
                                 TraceExportOutboxService exportOutboxService,
                                 CouncilProperties properties) {
        this.repository = repository;
        this.exportOutboxService = exportOutboxService;
        this.properties = properties;
    }

    @Scheduled(cron = "${council.trace.cleanup-cron:0 17 3 * * *}")
    @Transactional
    public void applyRetention() {
        stripOldRawDebugArtifacts();
        deleteExpiredTraces();
        purgeExportOutbox();
    }

    void stripOldRawDebugArtifacts() {
        int days = properties.getTrace().getRawDebugRetentionDays();
        if (days <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        List<TraceEntity> entities = repository.findTop100ByCreatedAtBeforeAndRawResponsesIsNotNull(cutoff);
        for (TraceEntity entity : entities) {
            entity.setRawResponses(null);
            entity.setDraftResults(null);
            entity.setCriticResult(null);
            entity.setJudgeResult(null);
        }
        if (!entities.isEmpty()) {
            repository.saveAll(entities);
            log.info("[trace-retention] Stripped raw debug artifacts from {} traces", entities.size());
        }
    }

    void deleteExpiredTraces() {
        int days = properties.getTrace().getRetentionDays();
        if (days <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        long deleted = repository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("[trace-retention] Deleted {} expired traces", deleted);
        }
    }

    void purgeExportOutbox() {
        int days = properties.getTrace().getExportRetentionDays();
        if (days <= 0 || exportOutboxService == null) {
            return;
        }
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        long deleted = exportOutboxService.purgeOlderThan(cutoff);
        if (deleted > 0) {
            log.info("[trace-retention] Deleted {} expired export outbox rows", deleted);
        }
    }
}
