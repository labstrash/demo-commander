package com.example.commander.scheduling;

import com.example.commander.domain.deadletter.DeadLetterMessageRow;
import com.example.commander.mq.MqResilienceProperties;
import com.example.commander.mq.MqResilienceProperties.Backoff;
import com.example.commander.mq.ResilientMqSender;
import com.example.commander.mq.SendOutcome;
import com.example.commander.repository.DeadLetterMessageRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Quartz job that actively recovers dead-lettered messages, polling {@code
 * CAMT.DeadLetterMessage} on its own cadence, independent of when reports happen to fire —
 * not part of {@code reportPipelineJob}, since dead-letter timing has nothing to do with
 * report schedules.
 *
 * <p>Reuses {@link ResilientMqSender} — the exact same classify → retry → circuit-breaker
 * path the primary writer uses, not a parallel implementation, so a failure is never treated
 * differently here than it is on the primary send path.
 *
 * <p>{@code @DisallowConcurrentExecution}: a poll that's still working through a large
 * backlog shouldn't overlap with the next scheduled firing.
 */
@Component
@DisallowConcurrentExecution
public class DeadLetterRecoveryJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRecoveryJob.class);

    /** Upper bound on rows processed per firing, so a large backlog can't make one poll run unbounded. */
    private static final int MAX_ROWS_PER_RUN = 100;

    private final DeadLetterMessageRepository repository;
    private final ResilientMqSender sender;
    private final MqResilienceProperties properties;
    private final Clock clock;

    public DeadLetterRecoveryJob(
            DeadLetterMessageRepository repository,
            ResilientMqSender sender,
            MqResilienceProperties properties,
            Clock clock) {
        this.repository = repository;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void execute(JobExecutionContext context) {
        List<DeadLetterMessageRow> due = repository.findDueForRetry(MAX_ROWS_PER_RUN);
        if (due.isEmpty()) {
            log.debug("Dead-letter recovery: nothing due");
            return;
        }

        log.info("Dead-letter recovery: {} row(s) due", due.size());
        for (DeadLetterMessageRow row : due) {
            recover(row);
        }
    }

    private void recover(DeadLetterMessageRow row) {
        SendOutcome outcome = sender.send(row.targetQueue(), row.messagePayload());

        if (!outcome.isFailure()) {
            repository.delete(row.id());
            log.info("Dead-letter recovery: id={}, messageId={} recovered, row deleted", row.id(), row.messageId());
            return;
        }

        int retryCount = row.retryCount() + 1;
        String lastError = outcome.cause() != null
                ? outcome.cause().getMessage()
                : outcome.type().name();

        if (retryCount >= row.maxRetries()) {
            repository.markFailed(row.id(), retryCount, lastError);
            log.warn(
                    "Dead-letter recovery: id={}, messageId={} exhausted max_retries={}, marking FAILED",
                    row.id(),
                    row.messageId(),
                    row.maxRetries());
            return;
        }

        Instant nextRetryAt = computeNextRetryAt(row.reportType(), retryCount);
        repository.markRetryScheduled(row.id(), retryCount, nextRetryAt, lastError);
        log.warn(
                "Dead-letter recovery: id={}, messageId={} attempt {} failed, next retry at {}",
                row.id(),
                row.messageId(),
                retryCount,
                nextRetryAt);
    }

    /**
     * Exponential backoff from the report type's configured base, capped at its configured
     * maximum — see {@link MqResilienceProperties#getDeadLetterRetryBackoff(String)} for how
     * report types are grouped into shared backoff tiers.
     */
    private Instant computeNextRetryAt(String reportType, int retryCount) {
        Backoff backoff = properties.getDeadLetterRetryBackoff(reportType);
        long backoffSeconds =
                Math.min(backoff.getBaseSeconds() * (1L << Math.min(retryCount - 1, 30)), backoff.getMaxSeconds());
        return clock.instant().plusSeconds(backoffSeconds);
    }
}
