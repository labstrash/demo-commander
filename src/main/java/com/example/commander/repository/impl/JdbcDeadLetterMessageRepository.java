package com.example.commander.repository.impl;

import com.example.commander.domain.deadletter.DeadLetterMessageRow;
import com.example.commander.repository.DeadLetterMessageRepository;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link DeadLetterMessageRepository}.
 *
 * <p>Every {@code DATETIME2} column is bound/read with an explicit UTC {@link Calendar} —
 * {@code PreparedStatement.setTimestamp}/{@code ResultSet.getTimestamp} without one silently
 * convert through the JVM's default timezone instead of leaving the instant alone, which on
 * a non-UTC host (this one runs {@code Europe/Stockholm}) shifts every stored/read value by
 * the zone offset. {@code GETUTCDATE()} (used SQL-side for {@code created_at}/{@code
 * updated_at} defaults and the {@code findDueForRetry} comparison) isn't affected — the
 * driver only touches values that cross the Java boundary.
 */
@Repository
public class JdbcDeadLetterMessageRepository implements DeadLetterMessageRepository {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static final RowMapper<DeadLetterMessageRow> ROW_MAPPER = (rs, rowNum) -> new DeadLetterMessageRow(
            rs.getLong("id"),
            rs.getString("message_id"),
            rs.getLong("report_config_id"),
            (Long) rs.getObject("agreement_scope_id", Long.class),
            rs.getString("report_type"),
            rs.getString("message_payload"),
            rs.getString("target_queue"),
            rs.getInt("retry_count"),
            rs.getInt("max_retries"),
            rs.getString("last_error"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("created_at", Calendar.getInstance(UTC))),
            toInstant(rs.getTimestamp("updated_at", Calendar.getInstance(UTC))),
            toInstant(rs.getTimestamp("next_retry_at", Calendar.getInstance(UTC))));

    private static final String INSERT_SQL = """
            INSERT INTO CAMT.DeadLetterMessage
                (message_id, report_config_id, agreement_scope_id, report_type, message_payload,
                 target_queue, retry_count, max_retries, status, next_retry_at)
            VALUES (?, ?, ?, ?, ?, ?, 0, ?, 'PENDING_RETRY', ?)
            """;

    private static final String FIND_DUE_FOR_RETRY_SQL = """
            SELECT id, message_id, report_config_id, agreement_scope_id, report_type, message_payload,
                   target_queue, retry_count, max_retries, last_error, status, created_at, updated_at, next_retry_at
            FROM CAMT.DeadLetterMessage
            WHERE status = 'PENDING_RETRY' AND next_retry_at <= GETUTCDATE()
            ORDER BY next_retry_at ASC
            OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
            """;

    private static final String DELETE_SQL = "DELETE FROM CAMT.DeadLetterMessage WHERE id = ?";

    private static final String MARK_RETRY_SCHEDULED_SQL = """
            UPDATE CAMT.DeadLetterMessage
            SET retry_count = ?, next_retry_at = ?, last_error = ?, updated_at = GETUTCDATE()
            WHERE id = ?
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE CAMT.DeadLetterMessage
            SET status = 'FAILED', retry_count = ?, last_error = ?, updated_at = GETUTCDATE()
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcDeadLetterMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(
            String messageId,
            long reportConfigId,
            Long agreementScopeId,
            String reportType,
            String messagePayload,
            String targetQueue,
            int maxRetries,
            Instant nextRetryAt) {
        jdbcTemplate.update(INSERT_SQL, ps -> {
            ps.setString(1, messageId);
            ps.setLong(2, reportConfigId);
            if (agreementScopeId != null) {
                ps.setLong(3, agreementScopeId);
            } else {
                ps.setNull(3, Types.BIGINT);
            }
            ps.setString(4, reportType);
            ps.setString(5, messagePayload);
            ps.setString(6, targetQueue);
            ps.setInt(7, maxRetries);
            ps.setTimestamp(8, Timestamp.from(nextRetryAt), Calendar.getInstance(UTC));
        });
    }

    @Override
    public List<DeadLetterMessageRow> findDueForRetry(int limit) {
        return jdbcTemplate.query(FIND_DUE_FOR_RETRY_SQL, ROW_MAPPER, limit);
    }

    @Override
    public void delete(long id) {
        jdbcTemplate.update(DELETE_SQL, id);
    }

    @Override
    public void markRetryScheduled(long id, int retryCount, Instant nextRetryAt, String lastError) {
        jdbcTemplate.update(MARK_RETRY_SCHEDULED_SQL, ps -> {
            ps.setInt(1, retryCount);
            ps.setTimestamp(2, Timestamp.from(nextRetryAt), Calendar.getInstance(UTC));
            ps.setString(3, lastError);
            ps.setLong(4, id);
        });
    }

    @Override
    public void markFailed(long id, int retryCount, String lastError) {
        jdbcTemplate.update(MARK_FAILED_SQL, retryCount, lastError, id);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
