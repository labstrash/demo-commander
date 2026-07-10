package com.example.commander.domain.config;

import java.util.List;

/**
 * An assembled agreement scope with its resolved payment type assignments.
 *
 * <p>Represents a fully hydrated scope node in the report configuration hierarchy,
 * containing all payment type assignments and their associated accounts or aliases
 * needed for report generation.
 *
 * @param id unique identifier for the agreement scope
 * @param reportConfigId ID of the parent report configuration
 * @param name human-readable name of the scope
 * @param paymentTypeAssignments list of payment type assignments for this scope
 */
public record AgreementScopeNode(
        long id, long reportConfigId, String name, List<PaymentTypeAssignmentNode> paymentTypeAssignments) {

    public AgreementScopeNode {
        paymentTypeAssignments = paymentTypeAssignments == null ? List.of() : List.copyOf(paymentTypeAssignments);
    }
}
