package com.example.commander.domain.config;

/**
 * An alias assignment linked to a payment type assignment.
 *
 * <p>Represents a named alias associated with a payment type for a given
 * agreement scope. Aliases provide an alternative way to group or reference
 * accounts without exposing specific account details.
 *
 * <p>Mutually exclusive with {@link AccountAssignmentRow} - a payment type assignment
 * can have either alias assignments or account assignments, but not both.
 *
 * @param id unique identifier for this assignment
 * @param paymentTypeAssignmentId ID of the parent payment type assignment
 * @param aliasId the alias identifier
 */
public record AliasAssignmentRow(long id, long paymentTypeAssignmentId, String aliasId) {}
