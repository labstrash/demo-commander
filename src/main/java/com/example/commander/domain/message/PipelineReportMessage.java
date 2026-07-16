package com.example.commander.domain.message;

import java.util.Objects;

/**
 * The report pipeline's chunk item type — wraps the wire-facing {@link OutboundReportMessage}
 * with internal-only identity fields the pipeline itself needs but the Executor never sees.
 *
 * <p>Only {@link #payload()} is ever serialized to MQ or logged by
 * {@code LoggingReportMessageWriter}. {@code reportConfigId}/{@code agreementScopeId} exist
 * solely so a later stage can populate {@code CAMT.DeadLetterMessage} without re-deriving
 * them — they are deliberately not part of {@code OutboundReportMessage} itself, which only
 * carries what the Executor actually needs.
 *
 * @param payload the wire-facing outbound message
 * @param reportConfigId surrogate ID of the originating {@code ReportConfig} row
 * @param agreementScopeId the single originating scope ID for unbundled messages, or
 *     {@code null} for bundled/config-only messages, which have no single scope
 */
public record PipelineReportMessage(OutboundReportMessage payload, long reportConfigId, Long agreementScopeId) {

    public PipelineReportMessage {
        Objects.requireNonNull(payload, "payload");
    }
}
