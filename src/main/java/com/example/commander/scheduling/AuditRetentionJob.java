package com.example.commander.scheduling;

import com.example.commander.audit.AuditRetentionProperties;
import com.example.commander.repository.ReportCommandAuditRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Quartz job that enforces {@code CAMT.ReportCommandAudit} retention, independent of the main
 * pipeline — same shape as {@link DeadLetterRecoveryJob}: its own cadence, bounded per-firing
 * deletes so a large backlog can't turn one firing into a long-held table lock, and {@code
 * @DisallowConcurrentExecution} so a firing still working through a backlog doesn't overlap
 * the next.
 *
 * <p>Keyed off {@code sent_at} — already indexed ({@code IX_ReportCommandAudit_SentAt}), so
 * no new index is needed to drive the deletion query. Deletes every row past the retention
 * window regardless of {@code status} (SENT/FAILED/SKIPPED_DUPLICATE) — retention is about
 * age, not outcome.
 */
@Component
@DisallowConcurrentExecution
public class AuditRetentionJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);

    private final ReportCommandAuditRepository repository;
    private final AuditRetentionProperties properties;
    private final Clock clock;

    public AuditRetentionJob(
            ReportCommandAuditRepository repository, AuditRetentionProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void execute(JobExecutionContext context) {
        Instant cutoff = clock.instant().minus(properties.getRetentionDays(), ChronoUnit.DAYS);
        int batchSize = properties.getBatchSize();
        int totalDeleted = 0;

        int deletedThisBatch;
        do {
            deletedThisBatch = repository.deleteOlderThan(cutoff, batchSize);
            totalDeleted += deletedThisBatch;
        } while (deletedThisBatch == batchSize);
        // A full batch means there may be more still due — keep going until a call returns
        // fewer than requested, meaning this firing's backlog is exhausted.

        if (totalDeleted > 0) {
            log.info("Audit retention: deleted {} row(s) with sent_at before {}", totalDeleted, cutoff);
        } else {
            log.debug("Audit retention: nothing due for deletion before {}", cutoff);
        }
    }
}
