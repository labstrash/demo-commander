package com.example.commander.domain.message;

/**
 * An alias included in an outbound report message, stripped of internal DB identity.
 *
 * <p>Deliberately excludes {@code id} and {@code paymentTypeAssignmentId} —
 * {@link com.example.commander.domain.config.AliasAssignmentRow} carries those for
 * internal tree assembly, but they have no meaning to the downstream Executor.
 *
 * @param aliasId the alias identifier
 */
public record AliasAllocation(String aliasId) {}
