package com.example.commander.domain.config;

/**
 * A payment type assignment linked to an agreement scope.
 *
 * <p>Represents the association between an agreement scope and a specific payment type,
 * determining which payment types are included when generating reports for that scope.
 *
 * <p>This is the first level in the configuration hierarchy where the number of
 * rows can be unbounded, requiring special handling for large result sets.
 *
 * @param id unique identifier for this assignment
 * @param agreementScopeId ID of the parent agreement scope
 * @param paymentType the payment type code
 */
public record PaymentTypeAssignmentRow(long id, long agreementScopeId, String paymentType) {}
