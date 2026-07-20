package com.example.commander.repository;

import com.example.commander.domain.audit.ReportCommandAuditEntry;
import java.time.Instant;

/**
 * Repository for {@code CAMT.ReportCommandAudit} — one row per send attempt, written by
 * {@code MqReportMessageWriter} (primary send) and {@code DeadLetterRecoveryJob} (recovery
 * resend), the only two places a {@code ResilientMqSender.send()} outcome is observed.
 *
 * <p>{@link #existsSent(String)} is the dedup pre-check — cheap, avoids most wasted sends,
 * but not itself the structural guarantee. That's {@code
 * UX_ReportCommandAudit_CorrelationId_Sent}, a filtered unique index on {@code
 * (correlation_id) WHERE status = 'SENT'} — {@link #insert(ReportCommandAuditEntry)} can
 * fail on that constraint if a concurrent attempt won the race the pre-check missed; callers
 * catch that specifically and treat it as "someone else already recorded success," not a
 * hard error.
 */
public interface ReportCommandAuditRepository {

    /**
     * Returns whether a row with this {@code correlationId} already has {@code
     * status = 'SENT'}.
     *
     * @param correlationId the logical message's deterministic correlation ID
     * @return {@code true} if this logical message has already been recorded as sent
     */
    boolean existsSent(String correlationId);

    /**
     * Inserts one audit row.
     *
     * @param entry the row to insert
     * @throws org.springframework.dao.DuplicateKeyException if a concurrent attempt already
     *     inserted a {@code SENT} row for this {@code correlationId} — the filtered unique
     *     index's structural backstop firing; callers should catch this specifically, not
     *     let it propagate as an unexpected failure
     */
    void insert(ReportCommandAuditEntry entry);

    /**
     * Deletes up to {@code limit} rows with {@code sent_at} older than {@code cutoff} —
     * bounded, so a large backlog can't turn one retention firing into one long-held table
     * lock. {@code AuditRetentionJob} calls this repeatedly per firing until a call returns
     * fewer than {@code limit}, meaning the backlog for this firing is exhausted.
     *
     * @param cutoff rows with {@code sent_at} strictly before this are eligible
     * @param limit maximum rows to delete in this call
     * @return the number of rows actually deleted
     */
    int deleteOlderThan(Instant cutoff, int limit);
}
