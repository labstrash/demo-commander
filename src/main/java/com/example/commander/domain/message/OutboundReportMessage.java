package com.example.commander.domain.message;

import com.example.commander.domain.config.AccountAssignmentRow;
import com.example.commander.domain.config.AliasAssignmentRow;
import java.time.Instant;
import java.util.List;

/**
 * An outbound message for report generation, produced by the fan-out and bundling process.
 *
 * <p>Each instance represents a single message to be published to the message queue,
 * containing all information needed to generate a report: configuration details,
 * window boundaries, recipient information, and account or alias data.
 *
 * <p>Messages can be:
 * <ul>
 *   <li><b>Config-only:</b> No account/alias data (zero-scope case)</li>
 *   <li><b>Bundled:</b> Merged accounts/aliases from multiple scopes</li>
 *   <li><b>Unbundled:</b> Single account or alias from a specific scope</li>
 * </ul>
 *
 * @param reportConfigId surrogate ID of the report configuration
 * @param configId business identifier of the report configuration
 * @param agreementScopeId ID of the originating scope (0 for bundled/config-only)
 * @param reportType type of report to generate
 * @param reportVersion version of the report
 * @param reportFrequency scheduling frequency
 * @param windowStartUtc start of the reporting window
 * @param windowEndUtc end of the reporting window
 * @param isBundled whether this message bundles multiple scopes
 * @param triggerType how the report was triggered
 * @param recipient the message recipient
 * @param accounts account assignments included in this message
 * @param aliases alias assignments included in this message
 * @param paymentTypeCount number of payment types included
 * @param requestorName who initiated the request (null for scheduled)
 */
public record OutboundReportMessage(
        long reportConfigId,
        int configId,
        long agreementScopeId,
        String reportType,
        String reportVersion,
        String reportFrequency,
        Instant windowStartUtc,
        Instant windowEndUtc,
        boolean isBundled,
        TriggerType triggerType,
        RecipientRef recipient,
        List<AccountAssignmentRow> accounts,
        List<AliasAssignmentRow> aliases,
        int paymentTypeCount,
        String requestorName) {

    public OutboundReportMessage {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    /**
     * Returns the number of account assignments in this message.
     *
     * @return account count
     */
    public int accountCount() {
        return accounts.size();
    }

    /**
     * Returns true if this message contains no account or alias data.
     *
     * <p>Config-only messages occur for report configurations with zero scopes.
     *
     * @return true if accounts and aliases are empty and paymentTypeCount is 0
     */
    public boolean isConfigOnly() {
        return accounts.isEmpty() && aliases.isEmpty() && paymentTypeCount == 0;
    }
}
