package com.example.commander.domain.message;

import java.time.Instant;
import java.util.Objects;

/**
 * Context information for assembling outbound report messages.
 *
 * <p>Contains data that supplements the {@code ReportConfigTree} during message assembly,
 * including window boundaries, report version, and caller context.
 *
 * <p>This context differs between scheduled and on-demand execution paths:
 * <ul>
 *   <li><b>Window boundaries:</b> Calculated by the scheduler for scheduled runs;
 *       provided directly by the request for on-demand</li>
 *   <li><b>Report version:</b> Uses the config's version for scheduled;
 *       request-supplied for on-demand</li>
 *   <li><b>Requestor name:</b> Null for scheduled; audit/traceability value for on-demand</li>
 * </ul>
 *
 * @param windowStartUtc start of the reporting window
 * @param windowEndUtc end of the reporting window
 * @param reportVersion version of the report to generate
 * @param triggerType how the report was triggered (scheduled or on-demand)
 * @param recipient the message recipient
 * @param requestorName who initiated the request (null for scheduled, auditable value for on-demand)
 */
public record MessageAssemblyContext(
        Instant windowStartUtc,
        Instant windowEndUtc,
        String reportVersion,
        TriggerType triggerType,
        RecipientRef recipient,
        String requestorName) {

    public MessageAssemblyContext {
        Objects.requireNonNull(windowStartUtc, "windowStartUtc");
        Objects.requireNonNull(windowEndUtc, "windowEndUtc");
        Objects.requireNonNull(reportVersion, "reportVersion");
        Objects.requireNonNull(triggerType, "triggerType");
        Objects.requireNonNull(recipient, "recipient");
    }
}
