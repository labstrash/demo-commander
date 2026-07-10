package com.example.commander.domain.config;

/**
 * An account assignment linked to a payment type assignment.
 *
 * <p>Represents a specific bank account associated with a payment type for a given
 * agreement scope. Each payment type assignment can have multiple account assignments.
 *
 * <p>Mutually exclusive with {@link AliasAssignmentRow} - a payment type assignment
 * can have either account assignments or alias assignments, but not both.
 *
 * @param id unique identifier for this assignment
 * @param paymentTypeAssignmentId ID of the parent payment type assignment
 * @param clearingNumber clearing/transit number for the account
 * @param accountNumber account identifier
 * @param accountBban Basic Bank Account Number (BBAN) format
 * @param currency ISO currency code for the account
 */
public record AccountAssignmentRow(
        long id,
        long paymentTypeAssignmentId,
        String clearingNumber,
        String accountNumber,
        String accountBban,
        String currency) {}
