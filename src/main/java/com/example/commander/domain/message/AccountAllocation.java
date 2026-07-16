package com.example.commander.domain.message;

/**
 * An account included in an outbound report message, stripped of internal DB identity.
 *
 * <p>Deliberately excludes {@code id} and {@code paymentTypeAssignmentId} —
 * {@link com.example.commander.domain.config.AccountAssignmentRow} carries those for
 * internal tree assembly, but they have no meaning to the downstream Executor.
 *
 * @param clearingNumber clearing/transit number for the account
 * @param accountNumber account identifier
 * @param accountBban Basic Bank Account Number (BBAN) format
 * @param currency ISO currency code for the account
 */
public record AccountAllocation(String clearingNumber, String accountNumber, String accountBban, String currency) {}
