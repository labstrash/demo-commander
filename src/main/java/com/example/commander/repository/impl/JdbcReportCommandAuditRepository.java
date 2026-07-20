package com.example.commander.repository.impl;

import com.example.commander.domain.audit.ReportCommandAuditEntry;
import com.example.commander.repository.ReportCommandAuditRepository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link ReportCommandAuditRepository}.
 *
 * <p>Every {@code DATETIME2} column is bound with an explicit UTC {@link Calendar} — same
 * reasoning as {@code JdbcDeadLetterMessageRepository}: {@code
 * PreparedStatement.setTimestamp} without one silently converts through the JVM's default
 * timezone instead of leaving the instant alone.
 */
@Repository
public class JdbcReportCommandAuditRepository implements ReportCommandAuditRepository {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static final String EXISTS_SENT_SQL = "SELECT CASE WHEN EXISTS ("
            + "SELECT 1 FROM CAMT.ReportCommandAudit WHERE correlation_id = ? AND status = 'SENT'"
            + ") THEN 1 ELSE 0 END";

    private static final String INSERT_SQL = """
            INSERT INTO CAMT.ReportCommandAudit
                (message_id, correlation_id, report_config_id, config_id, agreement_scope_id,
                 report_type, report_version, report_frequency, trigger_type,
                 window_start_utc, window_end_utc, is_bundled, account_count, payment_type_count,
                 recipient_type, recipient_value, mq_queue_name, mq_message_id,
                 status, error_message, sent_at, retry_count,
                 job_execution_id, step_execution_id, requestor_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    // SQL Server's DELETE TOP (N) doesn't accept a bind parameter for N — has to be a
    // literal in the statement text. Safe here: limit is always an int this class controls
    // the formatting of, never user/caller-supplied text.
    private static final String DELETE_OLDER_THAN_SQL_TEMPLATE =
            "DELETE TOP (%d) FROM CAMT.ReportCommandAudit WHERE sent_at < ?";

    private final JdbcTemplate jdbcTemplate;

    public JdbcReportCommandAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean existsSent(String correlationId) {
        Boolean exists = jdbcTemplate.queryForObject(EXISTS_SENT_SQL, Boolean.class, correlationId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void insert(ReportCommandAuditEntry entry) {
        jdbcTemplate.update(INSERT_SQL, ps -> {
            int i = 1;
            ps.setString(i++, entry.messageId());
            ps.setString(i++, entry.correlationId());
            setNullableLong(ps, i++, entry.reportConfigId());
            ps.setString(i++, entry.configId());
            setNullableLong(ps, i++, entry.agreementScopeId());
            ps.setString(i++, entry.reportType());
            ps.setString(i++, entry.reportVersion());
            ps.setString(i++, entry.reportFrequency());
            ps.setString(i++, entry.triggerType().name());
            ps.setTimestamp(i++, Timestamp.from(entry.windowStartUtc()), Calendar.getInstance(UTC));
            ps.setTimestamp(i++, Timestamp.from(entry.windowEndUtc()), Calendar.getInstance(UTC));
            ps.setBoolean(i++, entry.isBundled());
            ps.setInt(i++, entry.accountCount());
            ps.setInt(i++, entry.paymentTypeCount());
            ps.setString(i++, entry.recipientType());
            ps.setString(i++, entry.recipientValue());
            ps.setString(i++, entry.mqQueueName());
            ps.setString(i++, entry.mqMessageId());
            ps.setString(i++, entry.status().name());
            ps.setString(i++, entry.errorMessage());
            ps.setTimestamp(i++, Timestamp.from(entry.sentAt()), Calendar.getInstance(UTC));
            ps.setInt(i++, entry.retryCount());
            setNullableLong(ps, i++, entry.jobExecutionId());
            setNullableLong(ps, i++, entry.stepExecutionId());
            ps.setString(i, entry.requestorName());
        });
    }

    @Override
    public int deleteOlderThan(Instant cutoff, int limit) {
        String sql = DELETE_OLDER_THAN_SQL_TEMPLATE.formatted(limit);
        return jdbcTemplate.update(sql, ps -> ps.setTimestamp(1, Timestamp.from(cutoff), Calendar.getInstance(UTC)));
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value != null) {
            ps.setLong(index, value);
        } else {
            ps.setNull(index, Types.BIGINT);
        }
    }
}
