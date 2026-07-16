package com.example.commander.domain.message;

import java.time.Instant;
import java.util.List;

/**
 * An outbound message for report generation, produced by the fan-out and bundling process.
 *
 * <p>Each instance represents a single message to be published to the message queue,
 * containing all information needed to generate a report: configuration details,
 * window boundaries, recipient information, and account or alias data grouped by
 * payment type.
 *
 * <p>Messages can be:
 * <ul>
 *   <li><b>Config-only:</b> No payment type allocations (zero-scope case)</li>
 *   <li><b>Bundled:</b> One allocation per distinct payment type, merged across scopes</li>
 *   <li><b>Unbundled:</b> Single allocation with one account or alias from a specific scope</li>
 * </ul>
 *
 * @param configId business identifier of the report configuration
 * @param reportType type of report to generate
 * @param reportVersion version of the report
 * @param windowStartUtc start of the reporting window
 * @param windowEndUtc end of the reporting window
 * @param isBundled whether this message bundles multiple scopes
 * @param triggerType how the report was triggered
 * @param recipient the message recipient
 * @param paymentTypeAllocations accounts/aliases included in this message, grouped by payment type
 * @param requestorName who initiated the request (null for scheduled)
 * @param correlationId deterministic identifier, reproducible from message content — see
 *     {@code FanOutAssemblyService}; maps onto {@code ReportCommandAudit.correlation_id}
 * @param messageId non-deterministic identifier from {@link ReportMessageIdGenerator}, generated
 *     once at assembly time and threaded unchanged through every later transformation — maps
 *     onto {@code ReportCommandAudit.message_id}
 */
public record OutboundReportMessage(
        int configId,
        String reportType,
        String reportVersion,
        Instant windowStartUtc,
        Instant windowEndUtc,
        boolean isBundled,
        TriggerType triggerType,
        RecipientRef recipient,
        List<PaymentTypeAllocation> paymentTypeAllocations,
        String requestorName,
        String correlationId,
        String messageId) {

    public OutboundReportMessage {
        paymentTypeAllocations = paymentTypeAllocations == null ? List.of() : List.copyOf(paymentTypeAllocations);
    }

    /**
     * Returns the number of distinct payment types included in this message.
     *
     * @return payment type count
     */
    public int paymentTypeCount() {
        return paymentTypeAllocations.size();
    }

    /**
     * Returns the number of account assignments in this message, across all payment types.
     *
     * @return account count
     */
    public int accountCount() {
        return paymentTypeAllocations.stream()
                .mapToInt(allocation -> allocation.accounts().size())
                .sum();
    }

    /**
     * Returns true if this message contains no payment type allocations.
     *
     * <p>Config-only messages occur for report configurations with zero scopes.
     *
     * @return true if there are no payment type allocations
     */
    public boolean isConfigOnly() {
        return paymentTypeAllocations.isEmpty();
    }
}
