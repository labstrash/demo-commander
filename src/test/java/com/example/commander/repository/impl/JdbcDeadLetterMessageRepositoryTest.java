package com.example.commander.repository.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commander.domain.deadletter.DeadLetterMessageRow;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for {@link JdbcDeadLetterMessageRepository}, mocking {@link JdbcTemplate} the
 * same way {@code JdbcReportConfigLookupRepositoryTest} does — no real database needed.
 *
 * <p>{@code insert}/{@code markRetryScheduled} bind their {@code DATETIME2} parameter via a
 * {@link PreparedStatementSetter} (an explicit UTC {@link Calendar}, not the plain {@code
 * Object...} varargs form — see the class javadoc on {@link JdbcDeadLetterMessageRepository}
 * for why), so those tests capture and invoke the setter against a mock {@link
 * PreparedStatement} to verify what actually gets bound.
 */
@ExtendWith(MockitoExtension.class)
class JdbcDeadLetterMessageRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PreparedStatement preparedStatement;

    private JdbcDeadLetterMessageRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcDeadLetterMessageRepository(jdbcTemplate);
    }

    @Test
    void insertBindsEveryFieldIncludingNullAgreementScopeId() throws Exception {
        Instant nextRetryAt = Instant.parse("2026-07-16T00:00:00Z");

        repository.insert("MSG-1", 1L, null, "CAMT054C", "{}", "CAMT.054C.QUEUE", 5, nextRetryAt);

        PreparedStatementSetter setter = capturedSetter();
        setter.setValues(preparedStatement);

        verify(preparedStatement).setString(1, "MSG-1");
        verify(preparedStatement).setLong(2, 1L);
        verify(preparedStatement).setNull(3, Types.BIGINT);
        verify(preparedStatement).setString(4, "CAMT054C");
        verify(preparedStatement).setString(5, "{}");
        verify(preparedStatement).setString(6, "CAMT.054C.QUEUE");
        verify(preparedStatement).setInt(7, 5);
        verify(preparedStatement).setTimestamp(eq(8), eq(Timestamp.from(nextRetryAt)), any(Calendar.class));
    }

    @Test
    void insertBindsANonNullAgreementScopeIdForUnbundledMessages() throws Exception {
        Instant nextRetryAt = Instant.parse("2026-07-16T00:00:00Z");

        repository.insert("MSG-1", 1L, 101L, "CAMT054C", "{}", "CAMT.054C.QUEUE", 5, nextRetryAt);

        PreparedStatementSetter setter = capturedSetter();
        setter.setValues(preparedStatement);

        verify(preparedStatement).setLong(3, 101L);
    }

    @Test
    void findDueForRetryReturnsWhatTheQueryMaps() {
        DeadLetterMessageRow row = new DeadLetterMessageRow(
                1L,
                "MSG-1",
                1L,
                null,
                "CAMT054C",
                "{}",
                "CAMT.054C.QUEUE",
                0,
                5,
                null,
                "PENDING_RETRY",
                Instant.now(),
                null,
                Instant.now());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(50))).thenReturn(List.of(row));

        List<DeadLetterMessageRow> result = repository.findDueForRetry(50);

        assertThat(result).containsExactly(row);
    }

    @Test
    void deleteRemovesTheRowById() {
        repository.delete(42L);

        verify(jdbcTemplate).update(anyString(), eq(42L));
    }

    @Test
    void markRetryScheduledBindsRetryCountNextRetryAtAndLastError() throws Exception {
        Instant nextRetryAt = Instant.parse("2026-07-16T01:00:00Z");

        repository.markRetryScheduled(42L, 1, nextRetryAt, "boom");

        PreparedStatementSetter setter = capturedSetter();
        setter.setValues(preparedStatement);

        verify(preparedStatement).setInt(1, 1);
        verify(preparedStatement).setTimestamp(eq(2), eq(Timestamp.from(nextRetryAt)), any(Calendar.class));
        verify(preparedStatement).setString(3, "boom");
        verify(preparedStatement).setLong(4, 42L);
    }

    @Test
    void markFailedBindsFinalRetryCountAndLastError() {
        repository.markFailed(42L, 5, "boom");

        verify(jdbcTemplate).update(anyString(), eq(5), eq("boom"), eq(42L));
    }

    private PreparedStatementSetter capturedSetter() {
        ArgumentCaptor<PreparedStatementSetter> captor = ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbcTemplate).update(anyString(), captor.capture());
        return captor.getValue();
    }
}
