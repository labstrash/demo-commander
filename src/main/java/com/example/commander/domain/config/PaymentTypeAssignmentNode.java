package com.example.commander.domain.config;

import java.util.List;

/**
 * An assembled payment type assignment with its resolved accounts or aliases.
 *
 * <p>Represents a fully hydrated payment type assignment node in the report
 * configuration hierarchy. Each assignment can resolve to either a list of
 * accounts or a list of aliases, but never both.
 *
 * <p>This mutual exclusivity is enforced at construction time to maintain
 * data consistency and prevent invalid states.
 *
 * @param id unique identifier for this assignment
 * @param agreementScopeId ID of the parent agreement scope
 * @param paymentType the payment type code
 * @param accounts resolved account assignments (empty if alias assignment)
 * @param aliases resolved alias assignments (empty if account assignment)
 */
public record PaymentTypeAssignmentNode(
        long id,
        long agreementScopeId,
        String paymentType,
        List<AccountAssignmentRow> accounts,
        List<AliasAssignmentRow> aliases) {

    public PaymentTypeAssignmentNode {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        if (!accounts.isEmpty() && !aliases.isEmpty()) {
            throw new IllegalStateException("PaymentTypeAssignment " + id + " resolved to both accounts and aliases — "
                    + "this violates the account/alias mutual-exclusivity invariant and indicates "
                    + "either bad data or a bug in the staged read");
        }
    }

    /**
     * Returns true if this assignment resolved to account assignments.
     *
     * @return true if accounts list is not empty
     */
    public boolean isAccountAssignment() {
        return !accounts.isEmpty();
    }

    /**
     * Returns true if this assignment resolved to alias assignments.
     *
     * @return true if aliases list is not empty
     */
    public boolean isAliasAssignment() {
        return !aliases.isEmpty();
    }

    /**
     * Returns true if this assignment resolved to neither accounts nor aliases.
     *
     * <p>This indicates a dangling or incomplete assignment row.
     *
     * @return true if both accounts and aliases lists are empty
     */
    public boolean isEmpty() {
        return accounts.isEmpty() && aliases.isEmpty();
    }
}
