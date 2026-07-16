package com.example.commander.domain.deadletter;

import java.time.Instant;

/**
 * Flat projection of a {@code CAMT.DeadLetterMessage} row.
 *
 * @param id surrogate primary key
 * @param messageId the message's own identifier ({@code OutboundReportMessage.messageId()})
 * @param reportConfigId surrogate ID of the originating {@code ReportConfig} row
 * @param agreementScopeId the single originating scope ID for unbundled messages, or
 *     {@code null} for bundled/config-only messages, which have no single scope
 * @param reportType type of report the message is for
 * @param messagePayload the full serialized wire payload — a recovery attempt resends this
 *     directly, byte-for-byte, without re-deriving anything
 * @param targetQueue the MQ queue this message was headed for
 * @param retryCount number of recovery attempts made so far
 * @param maxRetries recovery attempts allowed before this row is marked terminally failed
 * @param lastError the most recent failure's message, or {@code null} if never retried
 * @param status current lifecycle status ({@code PENDING_RETRY} or {@code FAILED} — a
 *     successfully recovered row is deleted, not stamped with a terminal status)
 * @param createdAt when this row was first written
 * @param updatedAt when this row was last updated, or {@code null} if never updated
 * @param nextRetryAt when this row becomes eligible for the next recovery attempt
 */
public record DeadLetterMessageRow(
        long id,
        String messageId,
        long reportConfigId,
        Long agreementScopeId,
        String reportType,
        String messagePayload,
        String targetQueue,
        int retryCount,
        int maxRetries,
        String lastError,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant nextRetryAt) {}
