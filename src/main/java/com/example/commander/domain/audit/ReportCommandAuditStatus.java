package com.example.commander.domain.audit;

/**
 * Status of one {@code CAMT.ReportCommandAudit} row — one row per send attempt.
 *
 * <p>{@code REJECTED_CONFIG_NOT_ELIGIBLE}/{@code REJECTED_INVALID_WINDOW} are reserved names
 * (see the schema migration's header comment) for a future on-demand API's validation-time
 * rejections — not used by anything writing rows today, and deliberately not part of this
 * enum, which only covers the send-attempt outcomes the batch pipeline and dead-letter
 * recovery job actually produce.
 */
public enum ReportCommandAuditStatus {

    /** The message was successfully delivered to MQ. */
    SENT,

    /** The attempt failed — see {@code error_message} for which outcome and why. */
    FAILED,

    /**
     * The attempt was skipped before ever reaching MQ, because a row with this
     * {@code correlation_id} already has {@code status = SENT} — the dedup pre-check
     * caught it. Still recorded, not silently dropped, so a correctly-skipped duplicate
     * firing is visible rather than indistinguishable from "nothing happened."
     */
    SKIPPED_DUPLICATE
}
