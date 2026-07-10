package com.example.commander.repository.impl;

import com.example.commander.domain.config.RecipientRow;
import com.example.commander.domain.config.ReportConfigRow;
import com.example.commander.repository.ReportConfigLookupRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link ReportConfigLookupRepository}.
 *
 * <p>Performs single-row lookups for recipients and report configurations
 * used in on-demand report generation.
 *
 * <p><b>Ahead of the current phase</b> — see the class-level note on
 * {@link ReportConfigLookupRepository}. Not called from anywhere in the scheduled path;
 * confirm the on-demand lookup contract with the team before relying on it.
 */
@Repository
public class JdbcReportConfigLookupRepository implements ReportConfigLookupRepository {

    private static final String FIND_RECIPIENT_SQL =
            "SELECT Id, Type, Value, Name FROM CAMT.Recipient WHERE Type = ? AND Value = ?";

    private static final String FIND_CONFIG_BY_RECIPIENT_AND_TYPE_SQL = """
            SELECT Id, ConfigId, ReportType, ReportVersion, ReportFrequency, Description,
                   MessageRecipientId, AccountFormat, IsActive, IsPaginated, IsEmptyReportAllowed, IsBundled
            FROM CAMT.ReportConfig
            WHERE MessageRecipientId = ? AND ReportType = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcReportConfigLookupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<RecipientRow> findRecipientByTypeAndValue(String type, String value) {
        List<RecipientRow> rows =
                jdbcTemplate.query(FIND_RECIPIENT_SQL, ConfigurationRowMappers.RECIPIENT, type, value);
        return singleRow(rows);
    }

    @Override
    public Optional<ReportConfigRow> findByRecipientAndReportType(long messageRecipientId, String reportType) {
        // Matches UX_ReportConfig_RecipientReportType — a unique constraint that guarantees
        // at most one row, which is what makes singleRow() below safe to call here.
        List<ReportConfigRow> rows = jdbcTemplate.query(
                FIND_CONFIG_BY_RECIPIENT_AND_TYPE_SQL,
                ConfigurationRowMappers.REPORT_CONFIG,
                messageRecipientId,
                reportType);
        return singleRow(rows);
    }

    /**
     * Returns a single row from a list, or empty if the list is empty.
     *
     * @param rows the result list
     * @param <T> the row type
     * @return the single row if present, or empty
     * @throws IllegalStateException if more than one row is found
     */
    private static <T> Optional<T> singleRow(List<T> rows) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() > 1) {
            throw new IllegalStateException("Expected at most one row but found " + rows.size()
                    + " — this indicates a unique-constraint violation in the underlying data");
        }
        return Optional.of(rows.get(0));
    }
}
