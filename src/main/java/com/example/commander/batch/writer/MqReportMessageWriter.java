package com.example.commander.batch.writer;

import com.example.commander.domain.audit.ReportCommandAuditEntry;
import com.example.commander.domain.audit.ReportCommandAuditStatus;
import com.example.commander.domain.message.OutboundReportMessage;
import com.example.commander.domain.message.PipelineReportMessage;
import com.example.commander.mq.MqProperties;
import com.example.commander.mq.MqResilienceProperties;
import com.example.commander.mq.ResilientMqSender;
import com.example.commander.mq.SendOutcome;
import com.example.commander.repository.DeadLetterMessageRepository;
import com.example.commander.repository.ReportCommandAuditRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends each pipeline message's wire payload to the report type's configured MQ queue via
 * {@link ResilientMqSender}, writing one {@code CAMT.ReportCommandAudit} row per attempt and
 * dead-lettering anything {@link ResilientMqSender} couldn't deliver.
 *
 * <p><b>Dedup, before every send:</b> a pre-check ({@link
 * ReportCommandAuditRepository#existsSent(String)}) skips the send entirely — logging a
 * {@link ReportCommandAuditStatus#SKIPPED_DUPLICATE} row instead — if this message's {@code
 * correlationId} already has a {@code SENT} row. This is the cheap common-case guard, not
 * the structural guarantee: that's {@code UX_ReportCommandAudit_CorrelationId_Sent}, a
 * filtered unique index the audit {@code INSERT} itself can violate if a concurrent attempt
 * won the race this pre-check missed — {@link #insertAudit(ReportCommandAuditEntry)} catches
 * {@link DuplicateKeyException} specifically and treats it as "already recorded," not a
 * failure.
 *
 * <p>A message {@link ResilientMqSender} couldn't deliver (retries exhausted, permanent
 * failure, or the circuit breaker open) is written to {@code CAMT.DeadLetterMessage} instead
 * of failing this writer — the whole point of the dead-letter tier is that one message's
 * delivery trouble doesn't cascade into a failed batch job. Only a genuine failure to write
 * the dead-letter row itself (a DB problem) still propagates, same as any other unhandled
 * failure in the pipeline today.
 *
 * <p>Payload serialization happens here, before the resilient send — not inside {@link
 * ResilientMqSender}, which only ever handles an already-serialized string (the recovery job
 * resends a stored one, with nothing to serialize). A serialization failure on this flat,
 * JSR-310-aware record payload is realistically never expected; unlike a send failure, there
 * would be no valid payload to persist in {@code message_payload} for it, so it isn't
 * caught/dead-lettered here — it propagates and fails the job like any other unexpected,
 * programmer-error-class failure.
 *
 * <p>{@code @StepScope}: resolves {@code reportType}/{@code reportFrequency} from {@code
 * JobParameters}, the target queue, and {@code StepExecution} once per job execution — a
 * firing only ever processes one report type, so there's nothing to re-resolve mid-step.
 */
@Component
@StepScope
public class MqReportMessageWriter implements ItemWriter<PipelineReportMessage> {

    private static final Logger log = LoggerFactory.getLogger(MqReportMessageWriter.class);

    private final ObjectMapper objectMapper;
    private final ResilientMqSender sender;
    private final DeadLetterMessageRepository deadLetterRepository;
    private final ReportCommandAuditRepository auditRepository;
    private final Clock clock;
    private final String reportType;
    private final String reportFrequency;
    private final String targetQueue;
    private final int deadLetterMaxRetries;
    private final Long jobExecutionId;
    private final Long stepExecutionId;

    public MqReportMessageWriter(
            ObjectMapper objectMapper,
            ResilientMqSender sender,
            DeadLetterMessageRepository deadLetterRepository,
            ReportCommandAuditRepository auditRepository,
            Clock clock,
            MqProperties mqProperties,
            MqResilienceProperties mqResilienceProperties,
            @Value("#{jobParameters['reportType']}") String reportType,
            @Value("#{jobParameters['reportFrequency']}") String reportFrequency,
            @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId,
            @Value("#{stepExecution.id}") Long stepExecutionId) {
        this.objectMapper = objectMapper;
        this.sender = sender;
        this.deadLetterRepository = deadLetterRepository;
        this.auditRepository = auditRepository;
        this.clock = clock;
        this.reportType = reportType;
        this.reportFrequency = reportFrequency;
        this.targetQueue = mqProperties.queueFor(reportType);
        this.deadLetterMaxRetries = mqResilienceProperties.getDeadLetterMaxRetries();
        this.jobExecutionId = jobExecutionId;
        this.stepExecutionId = stepExecutionId;
    }

    @Override
    public void write(Chunk<? extends PipelineReportMessage> chunk) throws Exception {
        int sent = 0;
        int skipped = 0;
        for (PipelineReportMessage item : chunk) {
            OutboundReportMessage payload = item.payload();

            if (auditRepository.existsSent(payload.correlationId())) {
                log.info(
                        "Skipping send: correlationId={} already has a SENT audit row (messageId={})",
                        payload.correlationId(),
                        payload.messageId());
                insertAudit(auditEntry(item, ReportCommandAuditStatus.SKIPPED_DUPLICATE, null, null, 0));
                skipped++;
                continue;
            }

            String json = objectMapper.writeValueAsString(payload);
            SendOutcome outcome = sender.send(targetQueue, json);

            if (outcome.isFailure()) {
                deadLetter(item, json, outcome);
                insertAudit(auditEntry(item, ReportCommandAuditStatus.FAILED, null, errorMessage(outcome), 0));
            } else {
                insertAudit(auditEntry(item, ReportCommandAuditStatus.SENT, outcome.jmsMessageId(), null, 0));
                sent++;
            }
        }
        log.info(
                "Sent {}/{} message(s) to queue={} ({} skipped as duplicates)",
                sent,
                chunk.size(),
                targetQueue,
                skipped);
    }

    private void deadLetter(PipelineReportMessage item, String json, SendOutcome outcome) {
        log.warn(
                "Dead-lettering messageId={} (outcome={}) for queue={}",
                item.payload().messageId(),
                outcome.type(),
                targetQueue,
                outcome.cause());
        deadLetterRepository.insert(
                item.payload().messageId(),
                item.reportConfigId(),
                item.agreementScopeId(),
                reportType,
                json,
                targetQueue,
                deadLetterMaxRetries,
                clock.instant());
    }

    /**
     * Builds this attempt's audit row. {@code retryCount} is always 0 here — {@link
     * ResilientMqSender}'s in-process retries (Decision 6 in the MQ integration guide) are
     * opaque to this caller, so one call to {@code sender.send()} is one attempt from this
     * writer's point of view, regardless of how many JMS-level retries happened inside it.
     * {@code DeadLetterRecoveryJob} rows are the ones where {@code retryCount} means
     * something (the recovery attempt number).
     */
    private ReportCommandAuditEntry auditEntry(
            PipelineReportMessage item,
            ReportCommandAuditStatus status,
            String mqMessageId,
            String errorMessage,
            int retryCount) {
        OutboundReportMessage payload = item.payload();
        return ReportCommandAuditEntry.builder()
                .messageId(payload.messageId())
                .correlationId(payload.correlationId())
                .reportConfigId(item.reportConfigId())
                .configId(String.valueOf(payload.configId()))
                .agreementScopeId(item.agreementScopeId())
                .reportType(payload.reportType())
                .reportVersion(payload.reportVersion())
                .reportFrequency(reportFrequency)
                .triggerType(payload.triggerType())
                .windowStartUtc(payload.windowStartUtc())
                .windowEndUtc(payload.windowEndUtc())
                .isBundled(payload.isBundled())
                .accountCount(payload.accountCount())
                .paymentTypeCount(payload.paymentTypeCount())
                .recipientType(payload.recipient().type())
                .recipientValue(payload.recipient().value())
                .mqQueueName(targetQueue)
                .mqMessageId(mqMessageId)
                .status(status)
                .errorMessage(errorMessage)
                .sentAt(clock.instant())
                .retryCount(retryCount)
                .jobExecutionId(jobExecutionId)
                .stepExecutionId(stepExecutionId)
                .requestorName(payload.requestorName())
                .build();
    }

    private void insertAudit(ReportCommandAuditEntry entry) {
        try {
            auditRepository.insert(entry);
        } catch (DuplicateKeyException ex) {
            // The pre-check missed a race — a concurrent attempt already recorded SENT for
            // this correlationId between our check and this insert. The filtered unique
            // index caught it; that's exactly what it's for, not a failure to propagate.
            log.warn(
                    "Audit insert for correlationId={} hit the SENT dedup constraint — "
                            + "a concurrent attempt already recorded success",
                    entry.correlationId());
        }
    }

    private static String errorMessage(SendOutcome outcome) {
        String cause = outcome.cause() != null ? outcome.cause().getMessage() : null;
        return cause != null ? outcome.type() + ": " + cause : outcome.type().name();
    }
}
