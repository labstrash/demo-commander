package com.example.commander.repository;

import com.example.commander.domain.config.AccountAssignmentRow;
import com.example.commander.domain.config.AgreementScopeRow;
import com.example.commander.domain.config.AliasAssignmentRow;
import com.example.commander.domain.config.PaymentTypeAssignmentRow;
import com.example.commander.domain.config.ReportConfigRow;
import com.example.commander.domain.config.ReportConfigTree;
import java.util.Collection;
import java.util.List;

/**
 * Repository for staged, batched hierarchical reads of report configurations.
 *
 * <p>Performs reads in stages (configs → scopes → assignments → accounts/aliases)
 * using separate queries for each level. This avoids Cartesian product explosion
 * from SQL joins and keeps memory usage bounded.
 *
 * <p>Used by both scheduled batch processing (one page of configs at a time) and
 * on-demand report generation (single config).
 *
 * <p>Callers control memory usage by controlling how many configs they pass to
 * {@link #assembleTrees(List)} at once.
 */
public interface ConfigurationReadRepository {

    /**
     * Finds a keyset-paginated page of active report configurations.
     *
     * <p>Results are ordered by ID. For subsequent pages, pass the highest ID from
     * the previous page. A short page or empty result indicates no more rows.
     *
     * @param reportType exact report type to match (required — not a wildcard/optional filter)
     * @param reportFrequency exact report frequency to match (required — not a wildcard/optional filter)
     * @param lastSeenId highest ID already read (0 for first page)
     * @param pageSize maximum rows to return
     * @return list of report configurations for the requested page
     */
    List<ReportConfigRow> findConfigPage(String reportType, String reportFrequency, long lastSeenId, int pageSize);

    /**
     * Finds agreement scopes by parent configuration IDs.
     *
     * @param configIds collection of configuration IDs
     * @return list of agreement scope rows
     */
    List<AgreementScopeRow> findScopesByConfigIds(Collection<Long> configIds);

    /**
     * Finds payment type assignments by scope IDs.
     *
     * @param scopeIds collection of scope IDs
     * @return list of payment type assignment rows
     */
    List<PaymentTypeAssignmentRow> findAssignmentsByScopeIds(Collection<Long> scopeIds);

    /**
     * Finds account assignments by payment type assignment IDs.
     *
     * @param assignmentIds collection of assignment IDs
     * @return list of account assignment rows
     */
    List<AccountAssignmentRow> findAccountsByAssignmentIds(Collection<Long> assignmentIds);

    /**
     * Finds alias assignments by payment type assignment IDs.
     *
     * @param assignmentIds collection of assignment IDs
     * @return list of alias assignment rows
     */
    List<AliasAssignmentRow> findAliasesByAssignmentIds(Collection<Long> assignmentIds);

    /**
     * Performs a full staged read for the given configs and assembles the complete hierarchy.
     *
     * <p>Reads all levels (scopes, assignments, accounts/aliases) for the provided configs
     * and assembles them into a tree structure.
     *
     * @param configs the report configurations to read
     * @return list of fully assembled report configuration trees
     */
    List<ReportConfigTree> assembleTrees(List<ReportConfigRow> configs);
}
