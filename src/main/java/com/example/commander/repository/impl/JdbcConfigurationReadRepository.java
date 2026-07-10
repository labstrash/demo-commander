package com.example.commander.repository.impl;

import com.example.commander.config.ReadLayerProperties;
import com.example.commander.domain.config.AccountAssignmentRow;
import com.example.commander.domain.config.AgreementScopeRow;
import com.example.commander.domain.config.AliasAssignmentRow;
import com.example.commander.domain.config.PaymentTypeAssignmentRow;
import com.example.commander.domain.config.ReportConfigRow;
import com.example.commander.domain.config.ReportConfigTree;
import com.example.commander.domain.config.ReportConfigTreeAssembler;
import com.example.commander.repository.ConfigurationReadRepository;
import com.example.commander.repository.tvp.TvpParameterSource;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link ConfigurationReadRepository}.
 *
 * <p>Performs keyset-paginated reads of report configurations and staged hierarchical
 * reads of their associated scopes, assignments, and account/alias details.
 *
 * <p>Level 1→2 (scopes by config ID) is bounded by the page size and uses a plain
 * {@code IN (...)} query. Levels 2→3 and 3→4 (assignments, accounts, aliases) can be
 * unbounded and therefore use Table-Valued Parameters (TVP) to avoid SQL parameter limits.
 */
@Repository
public class JdbcConfigurationReadRepository implements ConfigurationReadRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcConfigurationReadRepository.class);

    /** Maximum safe size for the level 1→2 plain IN-list query. */
    private static final int SCOPE_QUERY_SAFE_IN_LIST_SIZE = 2000;

    private static final String FIND_CONFIG_PAGE_SQL = """
            SELECT Id, ConfigId, ReportType, ReportVersion, ReportFrequency, Description,
                   MessageRecipientId, AccountFormat, IsActive, IsPaginated, IsEmptyReportAllowed, IsBundled
            FROM CAMT.ReportConfig
            WHERE ReportType = :reportType
              AND ReportFrequency = :reportFrequency
              AND IsActive = 1
              AND Id > :lastSeenId
            ORDER BY Id
            OFFSET 0 ROWS FETCH NEXT :pageSize ROWS ONLY
            """;

    private static final String FIND_SCOPES_SQL = """
            SELECT ras.ReportConfigId AS reportConfigId, ags.Id AS scopeId, ags.Name AS scopeName
            FROM CAMT.ReportAgreementScope ras
            JOIN CAMT.AgreementScope ags ON ags.Id = ras.AgreementScopeId
            WHERE ras.ReportConfigId IN (:configIds)
            """;

    private static final String FIND_ASSIGNMENTS_SQL = """
            SELECT pta.Id AS assignmentId, pta.AgreementScopeId AS scopeId, pta.PaymentType AS paymentType
            FROM CAMT.PaymentTypeAssignment pta
            WHERE pta.AgreementScopeId IN (SELECT ids.Id FROM ? AS ids)
            """;

    private static final String FIND_ACCOUNTS_SQL = """
            SELECT aa.Id AS accountId, aa.PaymentTypeAssignmentId AS assignmentId, aa.ClearingNumber AS clearingNumber,
                   aa.AccountNumber AS accountNumber, aa.AccountBBAN AS accountBban, aa.Currency AS currency
            FROM CAMT.AccountAssignment aa
            WHERE aa.PaymentTypeAssignmentId IN (SELECT ids.Id FROM ? AS ids)
            """;

    private static final String FIND_ALIASES_SQL = """
            SELECT al.Id AS aliasRowId, al.PaymentTypeAssignmentId AS assignmentId, al.AliasId AS aliasId
            FROM CAMT.AliasAssignment al
            WHERE al.PaymentTypeAssignmentId IN (SELECT ids.Id FROM ? AS ids)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final ReadLayerProperties properties;

    public JdbcConfigurationReadRepository(JdbcTemplate jdbcTemplate, ReadLayerProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;

        // Create a scoped JdbcTemplate with its own timeout to avoid mutating the shared bean
        JdbcTemplate scopedJdbcTemplate = new JdbcTemplate(jdbcTemplate.getDataSource());
        scopedJdbcTemplate.setQueryTimeout(properties.getStagedQueryTimeoutSeconds());
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(scopedJdbcTemplate);
    }

    @Override
    public List<ReportConfigRow> findConfigPage(
            String reportType, String reportFrequency, long lastSeenId, int pageSize) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("reportType", reportType)
                .addValue("reportFrequency", reportFrequency)
                .addValue("lastSeenId", lastSeenId)
                .addValue("pageSize", pageSize);
        return namedJdbcTemplate.query(FIND_CONFIG_PAGE_SQL, params, ConfigurationRowMappers.REPORT_CONFIG);
    }

    @Override
    public List<AgreementScopeRow> findScopesByConfigIds(Collection<Long> configIds) {
        if (configIds.isEmpty()) {
            return List.of();
        }
        if (configIds.size() > SCOPE_QUERY_SAFE_IN_LIST_SIZE) {
            throw new IllegalStateException(
                    "findScopesByConfigIds called with " + configIds.size() + " ids, exceeding the safe IN(...) "
                            + "size of " + SCOPE_QUERY_SAFE_IN_LIST_SIZE + " — this should never happen given the "
                            + "configured reader page size and indicates trees are being assembled for too many "
                            + "configs at once");
        }

        MapSqlParameterSource params = new MapSqlParameterSource("configIds", configIds);
        return namedJdbcTemplate.query(FIND_SCOPES_SQL, params, ConfigurationRowMappers.AGREEMENT_SCOPE);
    }

    @Override
    public List<PaymentTypeAssignmentRow> findAssignmentsByScopeIds(Collection<Long> scopeIds) {
        if (scopeIds.isEmpty()) {
            return List.of();
        }
        return queryWithTvp(FIND_ASSIGNMENTS_SQL, scopeIds, ConfigurationRowMappers.PAYMENT_TYPE_ASSIGNMENT);
    }

    @Override
    public List<AccountAssignmentRow> findAccountsByAssignmentIds(Collection<Long> assignmentIds) {
        if (assignmentIds.isEmpty()) {
            return List.of();
        }
        return queryWithTvp(FIND_ACCOUNTS_SQL, assignmentIds, ConfigurationRowMappers.ACCOUNT_ASSIGNMENT);
    }

    @Override
    public List<AliasAssignmentRow> findAliasesByAssignmentIds(Collection<Long> assignmentIds) {
        if (assignmentIds.isEmpty()) {
            return List.of();
        }
        return queryWithTvp(FIND_ALIASES_SQL, assignmentIds, ConfigurationRowMappers.ALIAS_ASSIGNMENT);
    }

    @Override
    public List<ReportConfigTree> assembleTrees(List<ReportConfigRow> configs) {
        if (configs.isEmpty()) {
            return List.of();
        }

        long lastSeenId = configs.getLast().id();
        String reportType = configs.getFirst().reportType();
        String reportFrequency = configs.getFirst().reportFrequency();

        List<Long> configIds = configs.stream().map(ReportConfigRow::id).toList();
        List<AgreementScopeRow> scopes =
                fetchStage("scopes", reportType, reportFrequency, lastSeenId, () -> findScopesByConfigIds(configIds));

        List<Long> scopeIds = scopes.stream().map(AgreementScopeRow::id).toList();
        List<PaymentTypeAssignmentRow> assignments = fetchStage(
                "assignments", reportType, reportFrequency, lastSeenId, () -> findAssignmentsByScopeIds(scopeIds));

        List<Long> assignmentIds =
                assignments.stream().map(PaymentTypeAssignmentRow::id).toList();
        List<AccountAssignmentRow> accounts = fetchStage(
                "accounts", reportType, reportFrequency, lastSeenId, () -> findAccountsByAssignmentIds(assignmentIds));
        List<AliasAssignmentRow> aliases = fetchStage(
                "aliases", reportType, reportFrequency, lastSeenId, () -> findAliasesByAssignmentIds(assignmentIds));

        return ReportConfigTreeAssembler.assemble(configs, scopes, assignments, accounts, aliases);
    }

    private <T> List<T> fetchStage(
            String stageName, String reportType, String reportFrequency, long lastSeenId, Supplier<List<T>> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            // No partial persistence: this propagates as-is rather than swallowing.
            // Logged here only for debuggability (reportType/frequency/page position/stage).
            log.error(
                    "Staged read failed at stage={} for reportType={}, reportFrequency={}, page lastSeenId={}",
                    stageName,
                    reportType,
                    reportFrequency,
                    lastSeenId,
                    e);
            throw e;
        }
    }

    private <T> List<T> queryWithTvp(String sql, Collection<Long> ids, RowMapper<T> rowMapper) {
        return jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setQueryTimeout(properties.getTvpQueryTimeoutSeconds());
                    TvpParameterSource.bindIds(ps, 1, ids);
                },
                rowMapper);
    }
}
