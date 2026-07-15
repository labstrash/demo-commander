package com.example.commander.domain.message;

import com.example.commander.domain.config.AccountAssignmentRow;
import com.example.commander.domain.config.AliasAssignmentRow;
import java.util.List;

/**
 * The accounts or aliases allocated to a single payment type within an outbound message.
 *
 * <p>Preserves the payment type association that would otherwise be lost when bundling
 * merges rows from multiple {@code PaymentTypeAssignmentNode}s into one message: bundled
 * messages carry one allocation per distinct payment type across all merged scopes, while
 * unbundled messages carry exactly one allocation with a single account or alias.
 *
 * <p>Mutually exclusive with accounts and aliases both populated, matching the invariant
 * on {@code PaymentTypeAssignmentNode}.
 *
 * @param paymentType the payment type code
 * @param accounts account assignments for this payment type (empty if alias allocation)
 * @param aliases alias assignments for this payment type (empty if account allocation)
 */
public record PaymentTypeAllocation(
        String paymentType, List<AccountAssignmentRow> accounts, List<AliasAssignmentRow> aliases) {

    public PaymentTypeAllocation {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        if (!accounts.isEmpty() && !aliases.isEmpty()) {
            throw new IllegalStateException("PaymentTypeAllocation " + paymentType + " resolved to both accounts "
                    + "and aliases — this violates the account/alias mutual-exclusivity invariant");
        }
    }
}
