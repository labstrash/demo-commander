package com.example.commander.repository;

import com.example.commander.domain.config.RecipientRow;
import com.example.commander.domain.config.ReportConfigRow;
import java.util.Optional;

/**
 * Repository for single-row lookups used in on-demand report generation.
 *
 * <p>Provides direct lookups for recipients and report configurations by their
 * business keys. This is separate from {@link ConfigurationReadRepository}
 * which handles staged hierarchical reads for batch processing.
 *
 * <p><b>Ahead of the current phase:</b> the on-demand read path is its own phase, not yet
 * designed as its own guide, and this branch's scope is the scheduled path's fetch/assembly
 * logic only. This interface is not called from anywhere in the scheduled path — it was
 * added early, anticipating the on-demand path's needs, and bakes in a lookup contract
 * (single row per {@code messageRecipientId} + {@code reportType}) that hasn't been through
 * this team's usual decision-log process yet. Confirm this shape with the team before
 * building on top of it, or move it out to its own branch/PR when that phase is actually
 * picked up.
 */
public interface ReportConfigLookupRepository {

    /**
     * Finds a recipient by type and value.
     *
     * @param type recipient type (e.g., ORIGINATOR, BIC — validated by the {@code MessageRecipientType} enum)
     * @param value delivery address or endpoint
     * @return the recipient if found, or empty if no match
     */
    Optional<RecipientRow> findRecipientByTypeAndValue(String type, String value);

    /**
     * Finds a report configuration by recipient and report type.
     *
     * @param messageRecipientId ID of the message recipient
     * @param reportType type of report to generate
     * @return the report configuration if found, or empty if no match
     */
    Optional<ReportConfigRow> findByRecipientAndReportType(long messageRecipientId, String reportType);
}
