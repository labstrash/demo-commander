package com.example.commander.repository;

import com.example.commander.domain.deadletter.DeadLetterMessageRow;
import java.time.Instant;
import java.util.List;

/**
 * Repository for {@code CAMT.DeadLetterMessage} — messages {@link
 * com.example.commander.mq.ResilientMqSender} couldn't deliver (retries exhausted,
 * permanent failure, or the circuit breaker was open), and the active recovery job that
 * retries them later.
 */
public interface DeadLetterMessageRepository {

    /**
     * Inserts a new dead-letter row with {@code status='PENDING_RETRY'} and
     * {@code retry_count=0}.
     *
     * @param messageId the message's own identifier
     * @param reportConfigId surrogate ID of the originating {@code ReportConfig} row
     * @param agreementScopeId the single originating scope ID, or {@code null} for
     *     bundled/config-only messages
     * @param reportType type of report the message is for
     * @param messagePayload the full serialized wire payload
     * @param targetQueue the MQ queue this message was headed for
     * @param maxRetries recovery attempts allowed before this row is marked terminally failed
     * @param nextRetryAt when this row becomes eligible for the first recovery attempt
     */
    void insert(
            String messageId,
            long reportConfigId,
            Long agreementScopeId,
            String reportType,
            String messagePayload,
            String targetQueue,
            int maxRetries,
            Instant nextRetryAt);

    /**
     * Finds rows due for a recovery attempt: {@code status='PENDING_RETRY'} and
     * {@code next_retry_at <= now}, oldest first.
     *
     * @param limit maximum number of rows to return
     * @return the due rows, oldest {@code next_retry_at} first
     */
    List<DeadLetterMessageRow> findDueForRetry(int limit);

    /**
     * Deletes a row once it's been successfully recovered — a resent message needs no
     * further tracking, so it's removed rather than kept around with a terminal status.
     *
     * @param id the row's surrogate ID
     */
    void delete(long id);

    /**
     * Records a failed recovery attempt that hasn't yet exhausted {@code max_retries} —
     * updates {@code retry_count}, {@code next_retry_at}, {@code last_error}, and
     * {@code updated_at}, leaving {@code status='PENDING_RETRY'}.
     *
     * @param id the row's surrogate ID
     * @param retryCount the new attempt count
     * @param nextRetryAt when the next recovery attempt becomes eligible
     * @param lastError the failure's message
     */
    void markRetryScheduled(long id, int retryCount, Instant nextRetryAt, String lastError);

    /**
     * Marks a row terminally failed: {@code status='FAILED'}, {@code retry_count} set to its
     * final value, {@code last_error}, and {@code updated_at}. No further recovery attempts
     * are made — this is for manual/dashboard attention.
     *
     * @param id the row's surrogate ID
     * @param retryCount the final attempt count
     * @param lastError the failure's message
     */
    void markFailed(long id, int retryCount, String lastError);
}
