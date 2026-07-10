package com.example.commander.domain.config;

/**
 * A message recipient for report delivery.
 *
 * <p>Represents a recipient entity that should receive generated reports.
 * Recipients can be of various types (e.g., ORIGINATOR, BIC) and
 * include the identifier and display name information.
 *
 * @param id unique identifier for the recipient
 * @param type recipient type (e.g., ORIGINATOR, BIC — validated by the {@code MessageRecipientType} enum)
 * @param value unique identifier for the recipient
 * @param name human-readable display name for the recipient
 */
public record RecipientRow(long id, String type, String value, String name) {}
