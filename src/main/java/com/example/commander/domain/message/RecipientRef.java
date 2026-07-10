package com.example.commander.domain.message;

/**
 * A reference to a message recipient for report delivery.
 *
 * <p>Contains the essential recipient information needed for outbound messages:
 * delivery type, address, and display name.
 *
 * <p>This is distinct from the requestor (who initiated an on-demand request),
 * which is stored separately for audit and traceability purposes.
 *
 * @param id unique identifier of the recipient
 * @param type recipient type (e.g., ORIGINATOR, BIC — validated by the {@code MessageRecipientType} enum)
 * @param value unique identifier of the recipient
 * @param name human-readable display name
 */
public record RecipientRef(long id, String type, String value, String name) {}
