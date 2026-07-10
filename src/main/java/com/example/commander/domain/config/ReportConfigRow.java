package com.example.commander.domain.config;

/**
 * Flat projection of a report configuration row from the database.
 *
 * <p>Represents a single row from the ReportConfig table, containing all metadata
 * needed to generate a report: report type, version, frequency, recipient, and
 * formatting options.
 *
 * <p>This is a read-only projection used primarily for query results and report
 * generation orchestration.
 *
 * @param id surrogate primary key
 * @param configId application-computed business identifier (8-digit integer)
 * @param reportType type of report to generate
 * @param reportVersion version of the report specification
 * @param reportFrequency scheduling frequency for this report
 * @param description human-readable description of the report
 * @param messageRecipientId ID of the recipient to receive the report
 * @param accountFormat format specification for account data
 * @param isActive whether this configuration is currently active
 * @param isPaginated whether the report should be paginated
 * @param isEmptyReportAllowed whether empty reports are permitted
 * @param isBundled whether this report can be bundled with others
 */
public record ReportConfigRow(
        long id,
        int configId,
        String reportType,
        String reportVersion,
        String reportFrequency,
        String description,
        long messageRecipientId,
        String accountFormat,
        boolean isActive,
        boolean isPaginated,
        boolean isEmptyReportAllowed,
        boolean isBundled) {}
