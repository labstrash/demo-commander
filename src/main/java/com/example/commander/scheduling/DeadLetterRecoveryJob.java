package com.example.commander.scheduling;

import com.example.commander.domain.audit.ReportCommandAuditEntry;
import com.example.commander.domain.audit.ReportCommandAuditStatus;
import com.example.commander.domain.deadletter.DeadLetterMessageRow;
import com.example.commander.domain.message.OutboundReportMessage;
import com.example.commander.mq.MqResilienceProperties;
import com.example.commander.mq.MqResilienceProperties.Backoff;
import com.example.commander.mq.ResilientMqSender;
import com.example.commander.mq.SendOutcome;
import com.example.commander.repository.DeadLetterMessageRepository;
import com.example.commander.repository.ReportCommandAuditRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Quartz job that actively recovers dead-lettered messages, polling {@code
 * CAMT.DeadLetterMessage} on its own cadence, independent of when reports happen to fire —
 * not part of {@code reportPipelineJob}, since dead-letter timing has nothing to do with
 * report schedules.
 *
 * <p>Reuses {@link ResilientMqSender} — the exact same classify → retry → circuit-breaker
 * path the primary writer uses, not a parallel implementation, so a failure is never treated
 * differently here than it is on the primary send path. Also writes {@code
 * CAMT.ReportCommandAudit} rows through the same dedup-pre-check-then-insert pattern {@code
 * MqReportMessageWriter} uses — this job is the other of the only two places a {@code
 * ResilientMqSender.send()} outcome is observed.
 *
 * <p><b>{@code correlationId} isn't on {@link DeadLetterMessageRow}</b> — only {@code
 * messageId} is. It's recovered by deserializing {@link DeadLetterMessageRow#messagePayload()}
 * back into an {@link OutboundReportMessage} (the exact payload that would be resent,
 * byte-for-byte) rather than adding a column that would just duplicate data already present.
 * The same deserialization also supplies every other audit field this job can't otherwise
 * source (window bounds, recipient, bundling, etc.) — {@code DeadLetterMessageRow} itself
 * only carries what recovery scheduling needs, not the full message shape.
 *
 * <p>{@code job_execution_id}/{@code step_execution_id} stay {@code null} on every audit row
 * this job writes — it's a plain Quartz job, never inside a Spring Batch {@code
 * StepExecution} context, and both columns are already nullable for exactly this reason.
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
    private final ReportCommandAuditRepository auditRepository;
    private final ResilientMqSender sender;
    private final ObjectMapper objectMapper;
    private final MqResilienceProperties properties;
    private final Clock clock;

    public DeadLetterRecoveryJob(
            DeadLetterMessageRepository repository,
            ReportCommandAuditRepository auditRepository,
            ResilientMqSender sender,
            ObjectMapper objectMapper,
            MqResilienceProperties properties,
            Clock clock) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.sender = sender;
        this.objectMapper = objectMapper;
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
        OutboundReportMessage payload;
        try {
            payload = objectMapper.readValue(row.messagePayload(), OutboundReportMessage.class);
        } catch (Exception ex) {
            // Unrecoverable — the stored payload is the exact bytes that would be resent, so
            // a deserialization failure here means retrying will never help. No audit row:
            // most audit fields (window bounds, recipient, etc.) only exist inside the
            // payload we just failed to read.
            log.error(
                    "Dead-letter recovery: id={}, messageId={} has an unreadable message_payload, "
                            + "marking FAILED without retry",
                    row.id(),
                    row.messageId(),
                    ex);
            repository.markFailed(row.id(), row.retryCount(), "Unreadable message_payload: " + ex.getMessage());
            return;
        }

        if (auditRepository.existsSent(payload.correlationId())) {
            log.info(
                    "Dead-letter recovery: id={}, correlationId={} already has a SENT audit row — "
                            + "deleting as redundant instead of resending",
                    row.id(),
                    payload.correlationId());
            insertAudit(auditEntry(row, payload, ReportCommandAuditStatus.SKIPPED_DUPLICATE, null, null));
            repository.delete(row.id());
            return;
        }

        SendOutcome outcome = sender.send(row.targetQueue(), row.messagePayload());

        if (!outcome.isFailure()) {
            insertAudit(auditEntry(row, payload, ReportCommandAuditStatus.SENT, outcome.jmsMessageId(), null));
            repository.delete(row.id());
            log.info("Dead-letter recovery: id={}, messageId={} recovered, row deleted", row.id(), row.messageId());
            return;
        }

        int retryCount = row.retryCount() + 1;
        String lastError = outcome.cause() != null
                ? outcome.cause().getMessage()
                : outcome.type().name();
        insertAudit(auditEntry(row, payload, ReportCommandAuditStatus.FAILED, null, errorMessage(outcome)));

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

    private ReportCommandAuditEntry auditEntry(
            DeadLetterMessageRow row,
            OutboundReportMessage payload,
            ReportCommandAuditStatus status,
            String mqMessageId,
            String errorMessage) {
        return ReportCommandAuditEntry.builder()
                .messageId(payload.messageId())
                .correlationId(payload.correlationId())
                .reportConfigId(row.reportConfigId())
                .configId(String.valueOf(payload.configId()))
                .agreementScopeId(row.agreementScopeId())
                .reportType(payload.reportType())
                .reportVersion(payload.reportVersion())
                .reportFrequency(null) // no JobParameters context in a plain Quartz job
                .triggerType(payload.triggerType())
                .windowStartUtc(payload.windowStartUtc())
                .windowEndUtc(payload.windowEndUtc())
                .isBundled(payload.isBundled())
                .accountCount(payload.accountCount())
                .paymentTypeCount(payload.paymentTypeCount())
                .recipientType(payload.recipient().type())
                .recipientValue(payload.recipient().value())
                .mqQueueName(row.targetQueue())
                .mqMessageId(mqMessageId)
                .status(status)
                .errorMessage(errorMessage)
                .sentAt(clock.instant())
                .retryCount(row.retryCount() + 1) // the recovery attempt number, unlike the primary writer's always-0
                .jobExecutionId(null) // no Spring Batch StepExecution context here either
                .stepExecutionId(null)
                .requestorName(payload.requestorName())
                .build();
    }

    private void insertAudit(ReportCommandAuditEntry entry) {
        try {
            auditRepository.insert(entry);
        } catch (DuplicateKeyException ex) {
            // Same race the primary writer's pre-check can miss — a concurrent attempt
            // (the primary pipeline, or an overlapping recovery poll) already recorded
            // SENT for this correlationId between our check and this insert.
            log.warn(
                    "Dead-letter recovery: audit insert for correlationId={} hit the SENT dedup "
                            + "constraint — a concurrent attempt already recorded success",
                    entry.correlationId());
        }
    }

    private static String errorMessage(SendOutcome outcome) {
        String cause = outcome.cause() != null ? outcome.cause().getMessage() : null;
        return cause != null ? outcome.type() + ": " + cause : outcome.type().name();
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
